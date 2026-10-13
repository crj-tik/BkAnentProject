package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.rpc.RagListingDomainRpcServiceImpl;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagListingDomainRpcServiceTest {
    static RagAuthEnvelope auth(RagActorMode mode) {
        long now=Instant.now().getEpochSecond();
        return new RagAuthEnvelope(1,"agent-current","listing-master-service","agent-service",RagOperation.SUBMIT,
                "request","a".repeat(64),mode,mode==RagActorMode.USER?"7":"agent-service",now,now+60,mode==RagActorMode.USER?"b".repeat(64):null,null);
    }
    @Test void adminIndexCommitsDomainEventBeforeDispatchAndReturnsOnlyActualRagStatus() {
        var identity=mock(ListingRagIdentity.class); var backfill=mock(ListingRagBackfillService.class); var dispatcher=mock(ListingRagDispatcher.class);
        var service=new RagListingDomainRpcServiceImpl(identity,mock(ListingRagFacade.class),backfill,dispatcher,new ListingRagProperties());
        var credentials=new RagCredentialContext("admin-token");
        when(identity.requireAdmin(credentials)).thenReturn(new AuthRagPrincipal(7L,"ADMIN",true,true,Long.MAX_VALUE));
        var event=new RagIndexEvent(1,"event","event","listing.knowledge",RagDocumentIds.listing(11L),3,"1",RagIndexOperation.DELETE,"c".repeat(64),null,Instant.now());
        when(backfill.index(11L,credentials)).thenReturn(event);
        var status=new RagIndexStatus(1,"event","event","listing.knowledge",event.documentId(),3,event.contentHash(),RagIndexState.ACCEPTED,3,true,null,null,null);
        when(dispatcher.deliver("event")).thenReturn(status);
        assertSame(status,service.index(new RagListingIndexRequest(1,"request",11L),auth(RagActorMode.USER),credentials));
        var order=inOrder(identity,backfill,dispatcher);
        order.verify(identity).verifyCaller(any(),eq("request"),eq(RagOperation.SUBMIT),any(),eq(credentials));
        order.verify(identity).requireAdmin(credentials); order.verify(backfill).index(11L,credentials); order.verify(dispatcher).deliver("event");
    }
    @Test void backendFailureDoesNotReturnFalselyAcceptedQueuedDomainEvent() {
        var identity=mock(ListingRagIdentity.class); var backfill=mock(ListingRagBackfillService.class); var dispatcher=mock(ListingRagDispatcher.class);
        when(identity.requireAdmin(any())).thenReturn(new AuthRagPrincipal(7L,"ADMIN",true,true,Long.MAX_VALUE));
        var event=new RagIndexEvent(1,"event","event","listing.knowledge",RagDocumentIds.listing(11L),3,"1",RagIndexOperation.DELETE,"c".repeat(64),null,Instant.now());
        when(backfill.index(anyLong(),any())).thenReturn(event); when(dispatcher.deliver(any())).thenThrow(new RagException(RagErrorCode.TIMEOUT));
        var service=new RagListingDomainRpcServiceImpl(identity,mock(ListingRagFacade.class),backfill,dispatcher,new ListingRagProperties());
        assertEquals(RagErrorCode.TIMEOUT,assertThrows(RagException.class,()->service.index(new RagListingIndexRequest(1,"request",11L),auth(RagActorMode.USER),new RagCredentialContext("token"))).code());
    }
    @Test void serviceWriterCannotManageAndLegacyGateDefaultsClosedEvenWithSignedCaller() {
        var identity=mock(ListingRagIdentity.class);var backfill=mock(ListingRagBackfillService.class);var dispatcher=mock(ListingRagDispatcher.class);
        var service=new RagListingDomainRpcServiceImpl(identity,mock(ListingRagFacade.class),backfill,dispatcher,new ListingRagProperties());
        assertEquals(RagErrorCode.FORBIDDEN,assertThrows(RagException.class,()->service.index(new RagListingIndexRequest(1,"request",11L),auth(RagActorMode.SERVICE),null)).code());
        assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED,assertThrows(RagException.class,()->service.reconcile(new RagListingReconcileRequest(1,"request",11L,3,"c".repeat(64),true),auth(RagActorMode.SERVICE))).code());
        verifyNoInteractions(backfill,dispatcher);
    }
    @Test void realSignedReconciliationReturnsActualAcceptanceOnlyAfterLocalCommit() {
        var f = new ReconciliationFixture();
        var initial = f.transaction.execute(s -> f.outbox.publishCurrent(11L));
        var request = new RagListingReconcileRequest(1, "proof-upsert", 11L, initial.revision(), initial.contentHash(), false);
        var status = f.service.reconcile(request, signed(request, "agent-service", AGENT_SECRET));
        assertEquals(RagIndexState.ACCEPTED, status.state());
        assertEquals(2, status.revision());
        assertEquals(status.eventId(), f.outbox.revision(11L).eventId());
        assertEquals("ACCEPTED", f.jdbc.queryForObject("SELECT state FROM listing_rag_outbox WHERE event_id=?", String.class, status.eventId()));
        verify(f.rpc).submit(argThat(event -> event.operation() == RagIndexOperation.UPSERT
                && event.contentHash().equals(initial.contentHash()) && event.document().equals(initial.document())), any());
        verifyNoInteractions(f.authProvider);
        // The existing proof-carrying contract also accepts a committed physical deletion.
        f.entity = null;
        f.jdbc.update("DELETE FROM listing_info WHERE id=11");
        var deletion = f.transaction.execute(s -> f.outbox.publishCurrent(11L));
        var deleteRequest = new RagListingReconcileRequest(1, "proof-delete", 11L, deletion.revision(), deletion.contentHash(), true);
        var deleted = f.service.reconcile(deleteRequest, signed(deleteRequest, "agent-service", AGENT_SECRET));
        assertEquals(4, deleted.revision());
        assertEquals(RagIndexState.ACCEPTED, deleted.state());
        verify(f.rpc).submit(argThat(event -> event.operation() == RagIndexOperation.DELETE && event.revision() == 4 && event.document() == null), any());
    }

    @Test void realSignatureRequestBindingAndCallerMappingRejectBeforeAnyDomainOrBackendEffect() {
        var properties = signedProperties();
        var authProvider = mock(com.bkanent.common.rpc.RagPrincipalRpcService.class);
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("auth", authProvider);
        var identity = new ListingRagIdentity(properties, factory.getBeanProvider(com.bkanent.common.rpc.RagPrincipalRpcService.class));
        var backfill = mock(ListingRagBackfillService.class);
        var dispatcher = mock(ListingRagDispatcher.class);
        var service = new RagListingDomainRpcServiceImpl(identity, mock(ListingRagFacade.class), backfill, dispatcher, properties);
        var request = new RagListingReconcileRequest(1, "request", 11L, 3, "c".repeat(64), false);
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, () -> service.reconcile(request, null)).code());
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                () -> service.reconcile(request, signed(request, "agent-service", "wrong-independent-test-secret-at-least-32"))).code());
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                () -> service.reconcile(request, signed(request, "other-service", AGENT_SECRET))).code());
        var envelope = signed(request, "agent-service", AGENT_SECRET);
        for (var tampered : List.of(
                new RagListingReconcileRequest(1, "request", 12L, 3, "c".repeat(64), false),
                new RagListingReconcileRequest(1, "request", 11L, 4, "c".repeat(64), false),
                new RagListingReconcileRequest(1, "request", 11L, 3, "d".repeat(64), false),
                new RagListingReconcileRequest(1, "request", 11L, 3, "c".repeat(64), true),
                new RagListingReconcileRequest(1, "different-request", 11L, 3, "c".repeat(64), false))) {
            assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                    () -> service.reconcile(tampered, envelope)).code());
        }
        properties.setLegacyReconciliationEnabled(false);
        assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED, assertThrows(RagException.class,
                () -> service.reconcile(request, envelope)).code());
        verifyNoInteractions(backfill, dispatcher, authProvider);
    }

    @Test void signedButDriftedCurrentProjectionCannotPublishOrDispatch() {
        var f = new ReconciliationFixture();
        var initial = f.transaction.execute(s -> f.outbox.publishCurrent(11L));
        f.entity.setImageUrls("changed outside proven projection");
        var request = new RagListingReconcileRequest(1, "proof", 11L, initial.revision(), initial.contentHash(), false);
        assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED, assertThrows(RagException.class,
                () -> f.service.reconcile(request, signed(request, "agent-service", AGENT_SECRET))).code());
        assertEquals(1, f.outbox.revision(11L).revision());
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM listing_rag_outbox", Integer.class));
        verifyNoInteractions(f.rpc, f.authProvider);
    }

    @Test void reconciliationDispatchFailureKeepsCommittedRevisionImmutableEventAndRetryState() {
        var f = new ReconciliationFixture();
        var initial = f.transaction.execute(s -> f.outbox.publishCurrent(11L));
        var request = new RagListingReconcileRequest(1, "proof", 11L, initial.revision(), initial.contentHash(), false);
        doAnswer(call -> {
            var event = (RagIndexEvent) call.getArgument(0);
            f.assertCommitted(event);
            throw new RagException(RagErrorCode.TIMEOUT);
        }).when(f.rpc).submit(any(), any());
        assertEquals(RagErrorCode.TIMEOUT, assertThrows(RagException.class,
                () -> f.service.reconcile(request, signed(request, "agent-service", AGENT_SECRET))).code());
        var committed = f.outbox.revision(11L);
        assertEquals(2, committed.revision());
        assertEquals(initial.contentHash(), committed.hash());
        String payload = f.jdbc.queryForObject("SELECT payload FROM listing_rag_outbox WHERE event_id=?", String.class, committed.eventId());
        assertEquals("RETRY_PENDING", f.jdbc.queryForObject("SELECT state FROM listing_rag_outbox WHERE event_id=?", String.class, committed.eventId()));
        assertEquals(1, f.jdbc.queryForObject("SELECT attempts FROM listing_rag_outbox WHERE event_id=?", Integer.class, committed.eventId()));
        assertEquals("TIMEOUT", f.jdbc.queryForObject("SELECT error_code FROM listing_rag_outbox WHERE event_id=?", String.class, committed.eventId()));
        assertNotNull(f.jdbc.queryForObject("SELECT next_attempt_at FROM listing_rag_outbox WHERE event_id=?", java.sql.Timestamp.class, committed.eventId()));
        // Retrying old proof cannot create another event. Retry the existing immutable local event instead.
        assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED, assertThrows(RagException.class,
                () -> f.service.reconcile(request, signed(request, "agent-service", AGENT_SECRET))).code());
        doReturn(new RagIndexStatus(1, committed.eventId(), committed.eventId(), "listing.knowledge",
                RagDocumentIds.listing(11L), 2, committed.hash(), RagIndexState.ACCEPTED, 2, false, null, null, null))
                .when(f.rpc).getStatus(any(), any());
        assertEquals(RagIndexState.ACCEPTED, f.dispatcher.deliver(committed.eventId()).state());
        assertEquals(payload, f.jdbc.queryForObject("SELECT payload FROM listing_rag_outbox WHERE event_id=?", String.class, committed.eventId()));
        assertEquals(committed, f.outbox.revision(11L));
        assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM listing_rag_outbox", Integer.class));
        verify(f.rpc, times(1)).submit(any(), any());
    }

    private static final String AGENT_SECRET = "agent-independent-test-secret-01234567890123456789";

    private static ListingRagProperties signedProperties() {
        var properties = ListingRagIdentityTest.properties();
        var caller = new ListingRagProperties.TrustedCaller();
        caller.setCallerService("agent-service");
        caller.setSecret(AGENT_SECRET);
        properties.setTrustedCallers(java.util.Map.of("agent-current", caller));
        properties.setLegacyReconciliationEnabled(true);
        return properties;
    }

    private static RagAuthEnvelope signed(RagListingReconcileRequest request, String caller, String secret) {
        long now = Instant.now().getEpochSecond();
        return RagRequestSigner.sign(new RagAuthEnvelope(1, "agent-current", "listing-master-service", caller, RagOperation.SUBMIT,
                request.requestId(), RagCanonicalHash.requestHash(request), RagActorMode.SERVICE, caller, now, now + 60, null, null),
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static class ReconciliationFixture {
        final org.springframework.jdbc.core.JdbcTemplate jdbc;
        final org.springframework.transaction.support.TransactionTemplate transaction;
        final ListingRagOutbox outbox;
        final ListingRagDispatcher dispatcher;
        final RagListingDomainRpcServiceImpl service;
        final com.bkanent.common.rpc.RagIndexRpcService rpc = mock(com.bkanent.common.rpc.RagIndexRpcService.class);
        final com.bkanent.common.rpc.RagPrincipalRpcService authProvider = mock(com.bkanent.common.rpc.RagPrincipalRpcService.class);
        com.bkanent.listing.entity.ListingEntity entity;

        ReconciliationFixture() {
            var source = new org.h2.jdbcx.JdbcDataSource();
            source.setURL("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
            transaction = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            jdbc.execute("CREATE TABLE listing_info(id BIGINT PRIMARY KEY,deleted INT)");
            jdbc.execute("INSERT INTO listing_info VALUES(11,0)");
            jdbc.execute("CREATE TABLE listing_rag_revision(listing_id BIGINT PRIMARY KEY,revision BIGINT DEFAULT 0,tombstone BOOLEAN DEFAULT FALSE,content_hash VARCHAR(64),latest_event_id VARCHAR(64),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            jdbc.execute("CREATE TABLE listing_rag_grant(actor_user_id BIGINT,resource_id BIGINT,operation VARCHAR(32),active BOOLEAN,version BIGINT,managed_by BIGINT,updated_at TIMESTAMP,PRIMARY KEY(actor_user_id,resource_id,operation))");
            jdbc.execute("CREATE TABLE listing_rag_outbox(event_id VARCHAR(64) PRIMARY KEY,listing_id BIGINT,revision BIGINT,content_hash VARCHAR(64),payload CLOB,state VARCHAR(32),attempts INT,next_attempt_at TIMESTAMP,lease_until TIMESTAMP,lease_token VARCHAR(64),error_code VARCHAR(64),created_at TIMESTAMP,UNIQUE(listing_id,revision))");
            entity = new com.bkanent.listing.entity.ListingEntity();
            entity.setId(11L); entity.setTitle("proven complete listing"); entity.setAddress("滨江路"); entity.setStatus("ON_SALE");
            var mapper = mock(com.bkanent.listing.mapper.ListingMapper.class);
            when(mapper.selectById(11L)).thenAnswer(call -> entity);
            var json = new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            var properties = signedProperties();
            var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
            factory.addBean("index", rpc); factory.addBean("auth", authProvider);
            var identity = new ListingRagIdentity(properties, factory.getBeanProvider(com.bkanent.common.rpc.RagPrincipalRpcService.class));
            outbox = new ListingRagOutbox(jdbc, mapper, json, new com.bkanent.listing.converter.ListingConverter(),
                    new com.bkanent.listing.search.ListingKnowledgeDocumentAssembler(), properties);
            var backfill = new ListingRagBackfillService(jdbc, identity, outbox, transaction);
            dispatcher = new ListingRagDispatcher(jdbc, json, factory.getBeanProvider(com.bkanent.common.rpc.RagIndexRpcService.class), identity, properties);
            service = new RagListingDomainRpcServiceImpl(identity, mock(ListingRagFacade.class), backfill, dispatcher, properties);
            when(rpc.getStatus(any(), any())).thenAnswer(call -> {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                throw new RagException(RagErrorCode.NOT_FOUND);
            });
            when(rpc.submit(any(), any())).thenAnswer(call -> {
                var event = (RagIndexEvent) call.getArgument(0);
                assertCommitted(event);
                return new RagIndexStatus(1, event.requestId(), event.eventId(), event.spaceId(), event.documentId(), event.revision(),
                        event.contentHash(), RagIndexState.ACCEPTED, event.revision(), event.operation() == RagIndexOperation.DELETE, null, null, null);
            });
        }

        void assertCommitted(RagIndexEvent event) {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(event.eventId(), outbox.revision(11L).eventId());
            assertEquals(event.revision(), outbox.revision(11L).revision());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM listing_rag_outbox WHERE event_id=?", Integer.class, event.eventId()));
        }
    }

    @Test void mismatchedAuthenticatedActorCannotUseAdminRoleFromDifferentToken() {
        var identity=mock(ListingRagIdentity.class);var backfill=mock(ListingRagBackfillService.class);var dispatcher=mock(ListingRagDispatcher.class);
        when(identity.requireAdmin(any())).thenReturn(new AuthRagPrincipal(8L,"ADMIN",true,true,Long.MAX_VALUE));
        var service=new RagListingDomainRpcServiceImpl(identity,mock(ListingRagFacade.class),backfill,dispatcher,new ListingRagProperties());
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED,assertThrows(RagException.class,()->service.index(new RagListingIndexRequest(1,"request",11L),auth(RagActorMode.USER),new RagCredentialContext("different"))).code());
        verifyNoInteractions(backfill,dispatcher);
    }
}
