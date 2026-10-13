package com.bkanent.memory.service;

import com.bkanent.common.agent.SystemConstraintRecord;
import com.bkanent.common.rag.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRagLifecycleTest {
    @Test
    void rollbackLeavesNoSourceProjectionOrOutbox() {
        var f = new MemoryRagTestSupport();
        f.tx.executeWithoutResult(tx -> { f.preferences.upsert(MemoryRagTestSupport.preference("key", ".5", LocalDateTime.now())); tx.setRollbackOnly(); });
        assertEquals(0, f.count("user_preference_memory"));
        assertEquals(0, f.count("memory_rag_projection"));
        assertEquals(0, f.count("memory_rag_outbox"));
        assertThrows(IllegalStateException.class, f.projection::lock);
    }

    @Test
    void decayThresholdPhysicalDeleteAndRevivalHaveMonotonicEvents() {
        var f = new MemoryRagTestSupport();
        var preference = MemoryRagTestSupport.preference("a_b", ".1", LocalDateTime.now());
        String id = RagDocumentIds.preference("42", "a_b");
        f.upsert(preference);
        assertEquals(RagIndexOperation.UPSERT, f.latest(id).operation());
        f.tx.executeWithoutResult(tx -> f.preferences.decayConfidence("42", null));
        assertEquals(1, f.count("user_preference_memory"));
        assertEquals(0, f.preferences.findByUserId("42", null).size());
        assertEquals(2, f.latest(id).revision());
        assertEquals(RagIndexOperation.DELETE, f.latest(id).operation());
        f.tx.executeWithoutResult(tx -> f.preferences.decayConfidence("42", null));
        assertEquals(0, f.count("user_preference_memory"));
        assertEquals(3, f.latest(id).revision());
        f.upsert(preference);
        assertEquals(4, f.latest(id).revision());
        assertEquals(RagIndexOperation.UPSERT, f.latest(id).operation());
        assertEquals(4, f.count("memory_rag_outbox"));
    }

    @Test
    void bootstrapOnlyAddsMissingAndConstraintUpsertHandlesDisableRevive() {
        var f = new MemoryRagTestSupport();
        var seed = new SystemConstraintRecord("seed", "initial", "policy", "bootstrap", "tag", true);
        assertEquals(Boolean.TRUE, f.tx.execute(tx -> f.constraints.bootstrapMissing(seed)));
        f.tx.executeWithoutResult(tx -> f.constraints.upsert(new SystemConstraintRecord("seed", "human", "policy", "manual", "tag", false)));
        assertEquals(Boolean.FALSE, f.tx.execute(tx -> f.constraints.bootstrapMissing(seed)));
        String id = RagDocumentIds.constraint("seed");
        assertEquals(RagIndexOperation.DELETE, f.latest(id).operation());
        assertEquals("human", f.jdbc.queryForObject("SELECT constraint_text FROM system_constraint_memory WHERE constraint_key=?", String.class, "seed"));
        f.tx.executeWithoutResult(tx -> f.constraints.upsert(seed));
        assertEquals(3, f.latest(id).revision());
        assertEquals(RagIndexOperation.UPSERT, f.latest(id).operation());
        assertEquals(3, f.count("memory_rag_outbox"));
    }

    @Test
    void backfillContainsPersistentPhysicalDeletionAndLatestCompleteSnapshot() {
        var f = new MemoryRagTestSupport();
        f.upsert(MemoryRagTestSupport.preference("gone", ".05", LocalDateTime.now()));
        f.tx.executeWithoutResult(tx -> f.preferences.decayConfidence("42", null));
        f.upsert(MemoryRagTestSupport.preference("live", ".8", LocalDateTime.now()));
        var backfill = new MemoryRagBackfillService(f.jdbc, f.projection, f.security);
        var pages = backfillAll(f, backfill, 1);
        assertEquals(2, pages.stream().mapToInt(page -> page.receipts().size()).sum());
        assertTrue(pages.stream().allMatch(page -> page.scanned() <= 1 && page.receipts().size() <= 1));
        assertTrue(pages.stream().flatMap(page -> page.receipts().stream()).allMatch(receipt -> receipt.state().equals("QUEUED")));
        assertNull(pages.get(pages.size() - 1).nextCursor());
        assertEquals(3, f.latest(RagDocumentIds.preference("42", "gone")).revision());
        assertNull(f.latest(RagDocumentIds.preference("42", "gone")).document());
        var live = f.latest(RagDocumentIds.preference("42", "live"));
        assertEquals(2, live.revision());
        assertEquals("0.8", live.document().attributes().get("confidence").scalar());
        assertFalse(f.jdbc.queryForObject("SELECT event_payload FROM memory_rag_projection WHERE document_id=?", String.class, live.documentId()).contains("verified-access-token"));
    }

    @Test
    void backfillAndConcurrentDeleteSerializeAndConverge() throws Exception {
        var f = new MemoryRagTestSupport();
        f.upsert(MemoryRagTestSupport.preference("race", ".05", LocalDateTime.now()));
        var backfill = new MemoryRagBackfillService(f.jdbc, f.projection, f.security);
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var snapshot = pool.submit(() -> f.tx.execute(tx -> { f.projection.lock(); locked.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { throw new RuntimeException(ex); } return backfill.backfill(null, 1, f.credentials); }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var deletion = pool.submit(() -> f.tx.executeWithoutResult(tx -> f.preferences.decayConfidence("42", null)));
            assertFalse(deletion.isDone());
            release.countDown(); snapshot.get(5, TimeUnit.SECONDS); deletion.get(5, TimeUnit.SECONDS);
            var latest = f.latest(RagDocumentIds.preference("42", "race"));
            assertEquals(3, latest.revision());
            assertNull(latest.document());
            assertEquals(0, f.count("user_preference_memory"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void pagesBoundBothSourceAndPersistentIdentityScansAndDoNotClaimAppliedWatermarks() {
        var f = new MemoryRagTestSupport();
        f.upsert(MemoryRagTestSupport.preference("gone", ".05", LocalDateTime.now()));
        f.tx.executeWithoutResult(tx -> f.preferences.decayConfidence("42", null));
        f.upsert(MemoryRagTestSupport.preference("live", ".8", LocalDateTime.now()));
        // A pre-migration source with no projection must be included, not just projection identities.
        f.jdbc.update("INSERT INTO user_preference_memory(user_id,preference_key,preference_value,confidence,observation_count,deleted) VALUES('42','source-only','persisted',.8,1,0)");
        f.tx.executeWithoutResult(tx -> f.constraints.upsert(new SystemConstraintRecord("disabled", "human", "policy", "manual", null, false)));
        var backfill = new MemoryRagBackfillService(f.jdbc, f.projection, f.security);
        var pages = backfillAll(f, backfill, 1);
        var receipts = pages.stream().flatMap(page -> page.receipts().stream()).toList();
        assertEquals(4, receipts.size());
        assertEquals(4, receipts.stream().map(MemoryRagBackfillService.QueuedEvent::documentId).distinct().count());
        assertTrue(pages.stream().allMatch(page -> page.scanned() <= 1 && page.receipts().size() <= 1));
        assertTrue(pages.stream().anyMatch(page -> page.scanned() == 1 && page.receipts().isEmpty() && page.hasMore()));
        assertTrue(receipts.stream().allMatch(receipt -> receipt.state().equals("QUEUED")));
        assertTrue(receipts.stream().anyMatch(receipt -> receipt.documentId().equals(RagDocumentIds.preference("42", "source-only")) && receipt.revision() == 1));
        assertTrue(receipts.stream().anyMatch(receipt -> receipt.documentId().equals(RagDocumentIds.preference("42", "gone")) && receipt.operation() == RagIndexOperation.DELETE));
        assertTrue(receipts.stream().anyMatch(receipt -> receipt.documentId().equals(RagDocumentIds.constraint("disabled")) && receipt.operation() == RagIndexOperation.DELETE));
        assertEquals("persisted", f.latest(RagDocumentIds.preference("42", "source-only")).document().content().split(": ", 2)[1]);
        assertFalse(pages.get(pages.size() - 1).hasMore());
        assertNull(pages.get(pages.size() - 1).nextCursor());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_rag_outbox WHERE state<>'PENDING'", Integer.class));
    }

    @Test
    void constraintSourcePagesAndPhysicalDeletionIdentitiesKeepMonotonicLifecycle() {
        var f = new MemoryRagTestSupport();
        f.tx.executeWithoutResult(tx -> f.constraints.upsert(new SystemConstraintRecord("gone", "removed", "policy", "manual", null, true)));
        f.jdbc.update("DELETE FROM system_constraint_memory WHERE constraint_key='gone'");
        f.tx.executeWithoutResult(tx -> f.constraints.upsert(new SystemConstraintRecord("logical", "deleted", "policy", "manual", null, true)));
        f.jdbc.update("UPDATE system_constraint_memory SET deleted=1 WHERE constraint_key='logical'");
        f.jdbc.update("INSERT INTO system_constraint_memory(constraint_key,constraint_text,category,source,active,deleted) VALUES('source-only','current policy','policy','manual',TRUE,0)");
        var backfill = new MemoryRagBackfillService(f.jdbc, f.projection, f.security);
        var pages = backfillAll(f, backfill, 1);
        assertTrue(pages.stream().anyMatch(page -> page.nextCursor() != null && page.nextCursor().startsWith("c:") && !page.nextCursor().equals("c:0")));
        assertEquals(3, pages.stream().mapToInt(page -> page.receipts().size()).sum());
        assertEquals(2, f.latest(RagDocumentIds.constraint("gone")).revision());
        assertEquals(RagIndexOperation.DELETE, f.latest(RagDocumentIds.constraint("gone")).operation());
        assertEquals(RagIndexOperation.DELETE, f.latest(RagDocumentIds.constraint("logical")).operation());
        assertEquals(RagIndexOperation.UPSERT, f.latest(RagDocumentIds.constraint("source-only")).operation());
        f.tx.executeWithoutResult(tx -> f.constraints.upsert(new SystemConstraintRecord("gone", "recreated", "policy", "manual", null, true)));
        var identities = f.tx.execute(tx -> backfill.backfill("r::", 500, f.credentials));
        assertTrue(identities.receipts().isEmpty());
        assertEquals(3, f.latest(RagDocumentIds.constraint("gone")).revision());
        assertEquals(RagIndexOperation.UPSERT, f.latest(RagDocumentIds.constraint("gone")).operation());
    }

    @Test
    void pageFailureAfterFirstPublishRollsBackWholePageAndLowOrLogicalSourcesPublishTombstones() {
        var f = new MemoryRagTestSupport();
        f.upsert(MemoryRagTestSupport.preference("first", ".8", LocalDateTime.now()));
        f.jdbc.update("INSERT INTO user_preference_memory(user_id,preference_key,preference_value,confidence,observation_count,deleted) VALUES('42','','invalid',.8,1,0)");
        var backfill = new MemoryRagBackfillService(f.jdbc, f.projection, f.security);
        assertEquals(RagErrorCode.INVALID_ARGUMENT, assertThrows(RagException.class,
                () -> f.tx.execute(tx -> backfill.backfill(null, 500, f.credentials))).code());
        assertEquals(1, f.count("memory_rag_outbox"));
        assertEquals(1, f.latest(RagDocumentIds.preference("42", "first")).revision());
        f.jdbc.update("DELETE FROM user_preference_memory WHERE preference_key=''");
        f.upsert(MemoryRagTestSupport.preference("low", ".05", LocalDateTime.now()));
        f.jdbc.update("UPDATE user_preference_memory SET deleted=1 WHERE preference_key='first'");
        var page = f.tx.execute(tx -> backfill.backfill(null, 500, f.credentials));
        assertEquals(2, page.scanned());
        assertTrue(page.receipts().stream().allMatch(receipt -> receipt.operation() == RagIndexOperation.DELETE && receipt.revision() == 2));
        assertTrue(f.tx.execute(tx -> backfill.backfill("r::", 500, f.credentials)).receipts().isEmpty());
    }

    @Test
    void physicalDeleteBetweenSourcePagesIsRecoveredAndRecreatedSourceIsNotTombstoned() {
        var f = new MemoryRagTestSupport();
        f.upsert(MemoryRagTestSupport.preference("first", ".8", LocalDateTime.now()));
        f.upsert(MemoryRagTestSupport.preference("race", ".05", LocalDateTime.now()));
        var backfill = new MemoryRagBackfillService(f.jdbc, f.projection, f.security);
        var first = f.tx.execute(tx -> backfill.backfill(null, 1, f.credentials));
        assertEquals("p:1", first.nextCursor());
        f.tx.executeWithoutResult(tx -> f.preferences.decayConfidence("42", "first"));
        var next = f.tx.execute(tx -> backfill.backfill(first.nextCursor(), 1, f.credentials));
        assertEquals(0, next.scanned());
        assertEquals("c:0", next.nextCursor());
        var constraints = f.tx.execute(tx -> backfill.backfill(next.nextCursor(), 1, f.credentials));
        var deleted = f.tx.execute(tx -> backfill.backfill(constraints.nextCursor(), 500, f.credentials));
        assertEquals(1, deleted.receipts().size());
        assertEquals(RagIndexOperation.DELETE, deleted.receipts().get(0).operation());
        assertEquals(3, f.latest(RagDocumentIds.preference("42", "race")).revision());
        f.upsert(MemoryRagTestSupport.preference("race", ".9", LocalDateTime.now()));
        var identities = f.tx.execute(tx -> backfill.backfill("r::", 500, f.credentials));
        assertTrue(identities.receipts().isEmpty());
        assertEquals(4, f.latest(RagDocumentIds.preference("42", "race")).revision());
        assertEquals(RagIndexOperation.UPSERT, f.latest(RagDocumentIds.preference("42", "race")).operation());
    }

    @Test
    void pageWaitsForConcurrentUpdateThenRebuildsCommittedCompleteSource() throws Exception {
        var f = new MemoryRagTestSupport();
        f.upsert(MemoryRagTestSupport.preference("race", ".5", LocalDateTime.now()));
        var backfill = new MemoryRagBackfillService(f.jdbc, f.projection, f.security);
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var writer = pool.submit(() -> f.tx.executeWithoutResult(tx -> {
                f.projection.lock();
                locked.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new AssertionError(ex); }
                f.preferences.upsert(MemoryRagTestSupport.preference("race", ".9", LocalDateTime.now()));
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var page = pool.submit(() -> f.tx.execute(tx -> backfill.backfill(null, 1, f.credentials)));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> page.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            var receipt = page.get(5, TimeUnit.SECONDS).receipts().get(0);
            assertEquals(3, receipt.revision());
            assertEquals("0.9", f.latest(receipt.documentId()).document().attributes().get("confidence").scalar());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void backfillBoundsCursorAuthorizationAndRollbackLeaveNoPartialEvents() {
        var f = new MemoryRagTestSupport();
        f.upsert(MemoryRagTestSupport.preference("live", ".8", LocalDateTime.now()));
        var backfill = new MemoryRagBackfillService(f.jdbc, f.projection, f.security);
        for (int limit : java.util.List.of(0, -1, 501)) {
            assertEquals(RagErrorCode.INVALID_ARGUMENT, assertThrows(RagException.class,
                    () -> f.tx.execute(tx -> backfill.backfill(null, limit, f.credentials))).code());
        }
        for (String cursor : java.util.List.of("", "p:-1", "p:01", "p:9223372036854775808", "r:@@:@@", "p:0' OR 1=1 --")) {
            assertEquals(RagErrorCode.INVALID_ARGUMENT, assertThrows(RagException.class,
                    () -> f.tx.execute(tx -> backfill.backfill(cursor, 1, f.credentials))).code());
        }
        f.tx.executeWithoutResult(tx -> { backfill.backfill(null, 500, f.credentials); tx.setRollbackOnly(); });
        assertEquals(1, f.count("memory_rag_outbox"));
        assertEquals(1, f.latest(RagDocumentIds.preference("42", "live")).revision());
        f.properties.setConstraintAdminRoles(java.util.Set.of());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class,
                () -> f.tx.execute(tx -> backfill.backfill(null, 1, f.credentials))).code());
        assertEquals(1, f.count("memory_rag_outbox"));
    }

    @Test
    void originalControllerRouteDefaultsToOneBoundedPageAndReturnsReceipts() throws Exception {
        var backfill = org.mockito.Mockito.mock(MemoryRagBackfillService.class);
        var page = new MemoryRagBackfillService.BackfillPage("p:0", "p:100", 100, true,
                java.util.List.of(new MemoryRagBackfillService.QueuedEvent("event", "memory.preference", "document", 7, RagIndexOperation.UPSERT, "QUEUED")));
        org.mockito.Mockito.when(backfill.backfill(null, 100, new RagCredentialContext("token"))).thenReturn(page);
        var controller = new com.bkanent.memory.controller.MemoryController(null, null, null, null, null, null, null, null, backfill);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.bkanent.memory.controller.MemoryRagExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/memory/rag/backfill").header("Authorization", "Bearer token"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.nextCursor").value("p:100"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.hasMore").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.receipts[0].state").value("QUEUED"));
        org.mockito.Mockito.verify(backfill).backfill(null, 100, new RagCredentialContext("token"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/memory/rag/backfill"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        org.mockito.Mockito.verifyNoMoreInteractions(backfill);
    }

    private static java.util.List<MemoryRagBackfillService.BackfillPage> backfillAll(MemoryRagTestSupport f, MemoryRagBackfillService backfill, int limit) {
        var pages = new java.util.ArrayList<MemoryRagBackfillService.BackfillPage>();
        String cursor = null;
        do {
            String current = cursor;
            var page = f.tx.execute(tx -> backfill.backfill(current, limit, f.credentials));
            pages.add(page);
            assertTrue(pages.size() <= 20, "finite test scan");
            cursor = page.nextCursor();
        } while (cursor != null);
        return pages;
    }

    @Test
    void externallySuppliedSqlFragmentsRemainBoundValues() {
        var f = new MemoryRagTestSupport();
        f.upsert(MemoryRagTestSupport.preference("x' OR 1=1 --", ".8", LocalDateTime.now()));
        assertEquals(0, f.preferences.findByUserId("42' OR 1=1 --", null).size());
        f.tx.executeWithoutResult(tx -> f.preferences.decayConfidence("42' OR 1=1 --", null));
        assertEquals(1, f.count("user_preference_memory"));
        assertEquals(0, f.preferences.findByUserId("42", "deployment' OR 1=1 --").size());
    }
}
