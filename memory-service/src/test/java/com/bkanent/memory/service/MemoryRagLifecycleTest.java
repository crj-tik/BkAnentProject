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
        assertEquals(Integer.valueOf(2), f.tx.execute(tx -> backfill.backfill(f.credentials)));
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
            var snapshot = pool.submit(() -> f.tx.execute(tx -> { f.projection.lock(); locked.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { throw new RuntimeException(ex); } return backfill.backfill(f.credentials); }));
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
    void externallySuppliedSqlFragmentsRemainBoundValues() {
        var f = new MemoryRagTestSupport();
        f.upsert(MemoryRagTestSupport.preference("x' OR 1=1 --", ".8", LocalDateTime.now()));
        assertEquals(0, f.preferences.findByUserId("42' OR 1=1 --", null).size());
        f.tx.executeWithoutResult(tx -> f.preferences.decayConfidence("42' OR 1=1 --", null));
        assertEquals(1, f.count("user_preference_memory"));
        assertEquals(0, f.preferences.findByUserId("42", "deployment' OR 1=1 --").size());
    }
}
