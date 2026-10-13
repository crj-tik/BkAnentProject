package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingMapper;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingRagOutboxTest {
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    ListingMapper mapper;
    ListingRagOutbox outbox;
    ObjectMapper json;
    ListingEntity entity;
    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source); transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE listing_rag_revision(listing_id BIGINT PRIMARY KEY,revision BIGINT DEFAULT 0,tombstone BOOLEAN DEFAULT FALSE,content_hash VARCHAR(64),latest_event_id VARCHAR(64),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE listing_rag_grant(actor_user_id BIGINT,resource_id BIGINT,operation VARCHAR(32),active BOOLEAN,version BIGINT,managed_by BIGINT,updated_at TIMESTAMP,PRIMARY KEY(actor_user_id,resource_id,operation))");
        jdbc.execute("CREATE TABLE listing_rag_outbox(event_id VARCHAR(64) PRIMARY KEY,listing_id BIGINT,revision BIGINT,content_hash VARCHAR(64),payload CLOB,state VARCHAR(32),attempts INT,next_attempt_at TIMESTAMP,lease_until TIMESTAMP,lease_token VARCHAR(64),error_code VARCHAR(64),created_at TIMESTAMP,UNIQUE(listing_id,revision))");
        jdbc.execute("CREATE TABLE listing_info(id BIGINT PRIMARY KEY,deleted INT)");
        jdbc.update("INSERT INTO listing_info VALUES (11,0)");
        mapper = mock(ListingMapper.class); json = new ObjectMapper().registerModule(new JavaTimeModule());
        outbox = new ListingRagOutbox(jdbc, mapper, json, new ListingConverter(), new ListingKnowledgeDocumentAssembler(), ListingRagIdentityTest.properties());
        entity = new ListingEntity(); entity.setId(11L); entity.setTitle("old"); entity.setAddress("滨江路"); entity.setStatus("ON_SALE");
        entity.setOwnerName("PRIVATE OWNER"); entity.setCertificateNo("PRIVATE CERT");
        when(mapper.selectById(11L)).thenAnswer(call -> entity);
    }
    @Test void rollbackLeavesNoConsumableEventAndNoPersistentRevision() {
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> { outbox.publishCurrent(11L); throw new IllegalStateException("rollback"); }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM listing_rag_outbox", Integer.class));
        assertNull(outbox.revision(11L));
    }
    @Test void projectionRevisionAndTombstonePersistAndPrivateFieldsNeverEnterPayload() throws Exception {
        RagIndexEvent first = transaction.execute(s -> outbox.publishCurrent(11L));
        assertEquals(1, first.revision()); assertEquals("11", first.document().attributes().get("listingId").scalar());
        assertEquals("true", first.document().attributes().get("active").scalar());
        String body = json.writeValueAsString(first); assertFalse(body.contains("PRIVATE OWNER")); assertFalse(body.contains("PRIVATE CERT"));
        entity.setImageUrls("image-A");
        RagIndexEvent assets = transaction.execute(s -> outbox.publishCurrent(11L));
        assertEquals(2, assets.revision()); assertNotEquals(first.contentHash(), assets.contentHash());
        entity = null;
        RagIndexEvent delete = transaction.execute(s -> outbox.publishCurrent(11L));
        assertEquals(3, delete.revision()); assertEquals(RagIndexOperation.DELETE, delete.operation()); assertNull(delete.document());
        assertTrue(outbox.revision(11L).tombstone());
        var identity = mock(ListingRagIdentity.class);
        var backfill = new ListingRagBackfillService(jdbc, identity, outbox, transaction);
        jdbc.update("DELETE FROM listing_info WHERE id=11");
        RagIndexEvent replay = backfill.backfill(0, 100, new RagCredentialContext("admin")).get(0);
        assertEquals(4, replay.revision()); assertEquals(RagIndexOperation.DELETE, replay.operation());
        assertEquals(List.of(11L), jdbc.query("SELECT listing_id FROM listing_rag_revision", (rs,n) -> rs.getLong(1)));
    }
    @Test void backfillLocksAndReadsCurrentSnapshotNotEarlierScanAndConvergesWithUpdates() {
        var backfill = new ListingRagBackfillService(jdbc, mock(ListingRagIdentity.class), outbox, transaction);
        transaction.execute(s -> outbox.publishCurrent(11L));
        entity.setTitle("latest");
        RagIndexEvent replay = backfill.backfill(0, 1, new RagCredentialContext("admin")).get(0);
        assertEquals(2, replay.revision()); assertTrue(replay.document().content().contains("latest"));
        entity = null; jdbc.update("UPDATE listing_info SET deleted=1 WHERE id=11");
        transaction.execute(s -> outbox.publishCurrent(11L));
        var tombstone = backfill.backfill(0, 1, new RagCredentialContext("admin")).get(0);
        assertEquals(4, tombstone.revision()); assertEquals(RagIndexOperation.DELETE, tombstone.operation());
    }
    @Test void concurrentBackfillWaitsForWriterLockThenReadsCommittedSnapshot() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = pool.submit(() -> transaction.execute(s -> {
                outbox.lock(11L);
                entity.setTitle("writer-committed");
                ready.countDown();
                try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) { throw new AssertionError("release timeout"); } }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return outbox.publishCurrent(11L);
            }));
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var backfill = new ListingRagBackfillService(jdbc, mock(ListingRagIdentity.class), outbox, transaction);
            var replay = pool.submit(() -> backfill.backfill(0,1,new RagCredentialContext("admin")));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> replay.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(1, writer.get(5, java.util.concurrent.TimeUnit.SECONDS).revision());
            var latest = replay.get(5, java.util.concurrent.TimeUnit.SECONDS).get(0);
            assertEquals(2, latest.revision());
            assertTrue(latest.document().content().contains("writer-committed"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void staleLegacyDeleteAndUntrackedIdentityAreRejected() {
        var backfill = new ListingRagBackfillService(jdbc, mock(ListingRagIdentity.class), outbox, transaction);
        RagIndexEvent current = transaction.execute(s -> outbox.publishCurrent(11L));
        assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED, assertThrows(RagException.class,
                () -> backfill.reconcile(11L, current.revision(), current.contentHash(), true)).code());
        assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED, assertThrows(RagException.class,
                () -> backfill.reconcile(99L, 0, current.contentHash(), true)).code());
        assertNull(outbox.revision(99L));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM listing_rag_outbox", Integer.class));
    }
    @Test void reconciliationAcceptsMatchingCompleteLiveProjectionAndCommittedTombstone() {
        var backfill = new ListingRagBackfillService(jdbc, mock(ListingRagIdentity.class), outbox, transaction);
        RagIndexEvent current = transaction.execute(s -> outbox.publishCurrent(11L));
        RagIndexEvent replay = backfill.reconcile(11L, current.revision(), current.contentHash(), false);
        assertEquals(2, replay.revision());
        assertEquals(RagIndexOperation.UPSERT, replay.operation());
        assertEquals(current.document(), replay.document());
        assertEquals(current.contentHash(), replay.contentHash());
        assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED, assertThrows(RagException.class,
                () -> backfill.reconcile(11L, current.revision(), current.contentHash(), false)).code());
        entity = null;
        jdbc.update("DELETE FROM listing_info WHERE id=11");
        RagIndexEvent deletion = transaction.execute(s -> outbox.publishCurrent(11L));
        RagIndexEvent tombstone = backfill.reconcile(11L, deletion.revision(), deletion.contentHash(), true);
        assertEquals(4, tombstone.revision());
        assertEquals(RagIndexOperation.DELETE, tombstone.operation());
        assertNull(tombstone.document());
        assertEquals(deletion.contentHash(), tombstone.contentHash());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM listing_rag_outbox", Integer.class));
    }

    @Test void reconstructedBodyMetadataAndGrantDriftRejectBeforeAnyRevisionOrOutboxWrite() {
        var backfill = new ListingRagBackfillService(jdbc, mock(ListingRagIdentity.class), outbox, transaction);
        RagIndexEvent current = transaction.execute(s -> outbox.publishCurrent(11L));
        entity.setTitle("drifted body");
        assertReconciliationRejectedWithoutWrites(backfill, current, false);
        entity.setTitle("old");
        entity.setImageUrls("drifted asset metadata");
        assertReconciliationRejectedWithoutWrites(backfill, current, false);
        entity.setImageUrls(null);
        jdbc.update("INSERT INTO listing_rag_grant VALUES(7,11,'SEARCH',TRUE,1,1,CURRENT_TIMESTAMP)");
        assertReconciliationRejectedWithoutWrites(backfill, current, false);
        jdbc.update("DELETE FROM listing_rag_grant");
        assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED, assertThrows(RagException.class,
                () -> backfill.reconcile(11L, current.revision(), "f".repeat(64), false)).code());
        assertEquals(1, outbox.revision(11L).revision());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM listing_rag_outbox", Integer.class));
        // The original online writer still accepts a legitimate new complete projection.
        entity.setTitle("legitimate update");
        assertEquals(2, transaction.execute(s -> outbox.publishCurrent(11L)).revision());
    }

    @Test void sourceDeletionAndRecreationCannotReuseOldLiveOrTombstoneProof() {
        var backfill = new ListingRagBackfillService(jdbc, mock(ListingRagIdentity.class), outbox, transaction);
        RagIndexEvent live = transaction.execute(s -> outbox.publishCurrent(11L));
        ListingEntity saved = entity;
        entity = null;
        jdbc.update("DELETE FROM listing_info WHERE id=11");
        assertReconciliationRejectedWithoutWrites(backfill, live, false);
        RagIndexEvent tombstone = transaction.execute(s -> outbox.publishCurrent(11L));
        entity = saved;
        jdbc.update("INSERT INTO listing_info VALUES(11,0)");
        assertReconciliationRejectedWithoutWrites(backfill, tombstone, true);
        // Even with the source restored byte-for-byte, durable tombstone/revision cannot rewind.
        assertReconciliationRejectedWithoutWrites(backfill, live, false);
        RagIndexEvent recreated = transaction.execute(s -> outbox.publishCurrent(11L));
        assertEquals(3, recreated.revision());
        assertEquals(4, backfill.reconcile(11L, recreated.revision(), recreated.contentHash(), false).revision());
    }

    @Test void reconciliationWaitsForWriterAndRejectsProofSupersededWhileWaiting() throws Exception {
        RagIndexEvent initial = transaction.execute(s -> outbox.publishCurrent(11L));
        var backfill = new ListingRagBackfillService(jdbc, mock(ListingRagIdentity.class), outbox, transaction);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer = pool.submit(() -> transaction.execute(s -> {
                outbox.lock(11L);
                entity.setTitle("new committed source");
                locked.countDown();
                try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return outbox.publishCurrent(11L);
            }));
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var reconcile = pool.submit(() -> {
                started.countDown();
                return assertThrows(RagException.class, () -> backfill.reconcile(11L, initial.revision(), initial.contentHash(), false)).code();
            });
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> reconcile.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(2, writer.get(5, java.util.concurrent.TimeUnit.SECONDS).revision());
            assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED, reconcile.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM listing_rag_outbox", Integer.class));
            assertEquals(2, outbox.revision(11L).revision());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private void assertReconciliationRejectedWithoutWrites(ListingRagBackfillService backfill, RagIndexEvent proof, boolean deleteIntent) {
        ListingRagOutbox.Revision before = outbox.revision(11L);
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM listing_rag_outbox", Integer.class);
        assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED, assertThrows(RagException.class,
                () -> backfill.reconcile(11L, proof.revision(), proof.contentHash(), deleteIntent)).code());
        assertEquals(before, outbox.revision(11L));
        assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM listing_rag_outbox", Integer.class));
    }

    @Test void dispatcherHasZeroPrecommitCallsAndRetriesSameImmutableEventAfterUnknownOutcome() {
        RagIndexEvent event = transaction.execute(s -> outbox.publishCurrent(11L));
        RagIndexRpcService rpc = mock(RagIndexRpcService.class);
        when(rpc.getStatus(any(), any())).thenThrow(new RagException(RagErrorCode.NOT_FOUND));
        when(rpc.submit(any(), any())).thenThrow(new RagException(RagErrorCode.TIMEOUT));
        var factory = new StaticListableBeanFactory(); factory.addBean("index", rpc);
        var identity = new ListingRagIdentity(ListingRagIdentityTest.properties(), factory.getBeanProvider(com.bkanent.common.rpc.RagPrincipalRpcService.class));
        var dispatcher = new ListingRagDispatcher(jdbc, json, factory.getBeanProvider(RagIndexRpcService.class), identity, ListingRagIdentityTest.properties());
        assertThrows(IllegalStateException.class, () -> transaction.execute(s -> dispatcher.deliver(event.eventId())));
        verifyNoInteractions(rpc);
        assertEquals(RagErrorCode.TIMEOUT, assertThrows(RagException.class, () -> dispatcher.deliver(event.eventId())).code());
        assertEquals("RETRY_PENDING", jdbc.queryForObject("SELECT state FROM listing_rag_outbox", String.class));
        var accepted = new RagIndexStatus(1, event.requestId(), event.eventId(), event.spaceId(), event.documentId(), event.revision(),
                event.contentHash(), RagIndexState.ACCEPTED, event.revision(), false, null, null, null);
        doReturn(accepted).when(rpc).getStatus(any(), any());
        assertEquals(RagIndexState.ACCEPTED, dispatcher.deliver(event.eventId()).state());
        verify(rpc, times(1)).submit(eq(event), any());
        assertEquals(event.eventId(), outbox.revision(11L).eventId());
        assertEquals(1, outbox.revision(11L).revision());
    }
    @Test void grantsDefaultDenyBoundedScopeAndManagedChangeShareOutboxTransaction() {
        var identity = mock(ListingRagIdentity.class);
        when(identity.requireAdmin(any())).thenReturn(new AuthRagPrincipal(1L, "ADMIN", true, true, Long.MAX_VALUE));
        var grants = new ListingRagGrantService(jdbc, identity, outbox, ListingRagIdentityTest.properties());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> grants.allowed(7L)).code());
        transaction.execute(s -> { grants.manage(7L, 11L, true, new RagCredentialContext("admin")); return null; });
        // Direct instance invocation isn't proxied: wrap the whole service call in our fixture transaction.
        assertEquals(List.of("11"), grants.allowed(7L));
        assertEquals(1, outbox.revision(11L).revision());
        var principal = new AuthRagPrincipal(7L, "BROKER", true, true, java.time.Instant.now().getEpochSecond() + 3600);
        var request = ListingRagFacade.request(ListingRagFacadeTest.input(null,null,null));
        var scope = grants.scope(principal,request);
        RagScopeSigner.verify(scope, new RagSigningKey("listing-current","listing-master-service","listing-master-service",
                Set.of(RagOperation.SEARCH), ListingRagIdentityTest.DOMAIN.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "listing.knowledge","7",RagOperation.SEARCH,RagCanonicalHash.requestHash(request),"listing-master-service","listing-v1",java.time.Instant.now());
        assertEquals(List.of("11"),scope.allowedResourceIds()); assertTrue(scope.activeOnly());
        assertTrue(scope.expiresAtEpochSecond()-scope.issuedAtEpochSecond()<=60);
        assertThrows(RagException.class,()->RagScopeSigner.verify(scope, new RagSigningKey("listing-current","listing-master-service","listing-master-service",
                Set.of(RagOperation.SEARCH),ListingRagIdentityTest.DOMAIN.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "listing.knowledge","8",RagOperation.SEARCH,RagCanonicalHash.requestHash(request),"listing-master-service","listing-v1",java.time.Instant.now()));
        assertThrows(IllegalStateException.class,()->transaction.execute(s->{grants.manage(7L,11L,false,new RagCredentialContext("admin"));throw new IllegalStateException("rollback revoke");}));
        assertEquals(List.of("11"),grants.allowed(7L)); assertEquals(1,outbox.revision(11L).revision());
        jdbc.batchUpdate("INSERT INTO listing_rag_grant VALUES (7,?,'SEARCH',TRUE,1,1,CURRENT_TIMESTAMP)",
                java.util.stream.LongStream.rangeClosed(100, 5100).mapToObj(id -> new Object[]{id}).toList());
        assertEquals(RagErrorCode.RESOURCE_SCOPE_TOO_LARGE, assertThrows(RagException.class, () -> grants.allowed(7L)).code());
    }
}
