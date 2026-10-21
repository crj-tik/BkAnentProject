package com.bkanent.memory.service;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagIndexRpcService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryRagOutboxDispatcherTest {
    @Test
    void submitTimeoutRetriesSameIdentityAndStatusTimeoutRetainsRecoverableEvent() {
        // Force the sub-microsecond rounding window instead of depending on wall-clock speed.
        Instant now = Instant.parse("2026-10-10T12:00:00.123456800Z");
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(now);
            var f = new MemoryRagTestSupport();
            f.upsert(MemoryRagTestSupport.preference("key", ".8", LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
            f.properties.setDispatcherEnabled(true);
            List<RagIndexEvent> sent = new ArrayList<>();
            var fake = new RagIndexRpcService() {
                public RagIndexStatus submit(RagIndexEvent event, RagAuthEnvelope auth) {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    assertEquals(RagCanonicalHash.requestHash(event), auth.requestHash());
                    sent.add(event);
                    if (sent.size() == 1) throw new RagException(RagErrorCode.TIMEOUT);
                    return status(event, RagIndexState.ACCEPTED);
                }
                public RagIndexStatus getStatus(RagStatusRequest request, RagAuthEnvelope auth) { throw new RagException(RagErrorCode.TIMEOUT); }
            };
            var dispatcher = new MemoryRagOutboxDispatcher(f.jdbc, f.projection, f.security, fake, f.properties);
            // A freshly persisted TIMESTAMP may round above now; explicitly schedule each dispatch.
            due(f); dispatcher.dispatch();
            assertEquals("PENDING", state(f));
            assertEquals(1, f.jdbc.queryForObject("SELECT attempts FROM memory_rag_outbox", Integer.class));
            assertEquals("TIMEOUT", f.jdbc.queryForObject("SELECT error_code FROM memory_rag_outbox", String.class));
            due(f); dispatcher.dispatch();
            assertEquals(2, sent.size());
            assertEquals(sent.get(0).eventId(), sent.get(1).eventId());
            assertEquals(sent.get(0).revision(), sent.get(1).revision());
            assertEquals("WAITING", state(f));
            due(f); dispatcher.dispatch();
            assertEquals("PENDING", state(f));
            assertEquals(1, f.jdbc.queryForObject("SELECT attempts FROM memory_rag_outbox", Integer.class));
            assertEquals("TIMEOUT", f.jdbc.queryForObject("SELECT error_code FROM memory_rag_outbox", String.class));
            due(f); dispatcher.dispatch();
            assertEquals(3, sent.size());
            assertEquals(sent.get(0).eventId(), sent.get(2).eventId());
        }
    }
    @Test
    void appliedStateIsConfirmedNotInferredFromSubmitAndDisabledDispatchMakesNoCalls() {
        var f = new MemoryRagTestSupport(); f.upsert(MemoryRagTestSupport.preference("key", ".8", LocalDateTime.now()));
        var fake = new RagIndexRpcService() {
            public RagIndexStatus submit(RagIndexEvent event, RagAuthEnvelope auth) { return status(event, RagIndexState.APPLIED); }
            public RagIndexStatus getStatus(RagStatusRequest request, RagAuthEnvelope auth) { fail("not needed"); return null; }
        };
        var dispatcher = new MemoryRagOutboxDispatcher(f.jdbc, f.projection, f.security, fake, f.properties);
        dispatcher.dispatch(); assertEquals("PENDING", state(f));
        f.properties.setDispatcherEnabled(true); due(f); dispatcher.dispatch(); assertEquals("APPLIED", state(f));
        assertThrows(IllegalStateException.class, () -> f.tx.executeWithoutResult(tx -> dispatcher.dispatch()));
    }
    @Test
    void roundedInitialScheduleIsSkippedUntilPersistedTimestampIsDue() {
        var f = new MemoryRagTestSupport();
        f.properties.setDispatcherEnabled(true);
        Instant createdAt = Instant.parse("2026-10-10T12:00:00.123456700Z");
        Instant cutoff = createdAt.plusNanos(100);
        Instant rounded = Instant.parse("2026-10-10T12:00:00.123457Z");
        List<RagIndexEvent> sent = new ArrayList<>();
        var fake = new RagIndexRpcService() {
            public RagIndexStatus submit(RagIndexEvent event, RagAuthEnvelope auth) { sent.add(event); return status(event, RagIndexState.APPLIED); }
            public RagIndexStatus getStatus(RagStatusRequest request, RagAuthEnvelope auth) { fail("not yet submitted"); return null; }
        };
        var dispatcher = new MemoryRagOutboxDispatcher(f.jdbc, f.projection, f.security, fake, f.properties);
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(createdAt);
            f.upsert(MemoryRagTestSupport.preference("key", ".8", LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC)));
            time.when(Instant::now).thenReturn(cutoff);
            assertEquals(6, f.jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_name='memory_rag_outbox' AND column_name='next_attempt_at'", Integer.class));
            assertEquals(rounded, f.jdbc.queryForObject("SELECT next_attempt_at FROM memory_rag_outbox", Timestamp.class).toInstant());
            assertEquals(0, eligible(f, cutoff));
            dispatcher.dispatch();
            assertTrue(sent.isEmpty());
            assertEquals("PENDING", state(f));
            assertEquals(0, f.jdbc.queryForObject("SELECT attempts FROM memory_rag_outbox", Integer.class));
            assertNull(f.jdbc.queryForObject("SELECT error_code FROM memory_rag_outbox", String.class));
            time.when(Instant::now).thenReturn(rounded);
            assertEquals(1, eligible(f, rounded));
            dispatcher.dispatch();
            assertEquals(1, sent.size());
            assertEquals("APPLIED", state(f));
        }
    }
    @Test
    void expiredLeaseCanBeTakenOverAndLateWorkerCannotOverwriteNewerState() throws Exception {
        var f = new MemoryRagTestSupport();
        f.upsert(MemoryRagTestSupport.preference("lease", ".8", LocalDateTime.now()));
        f.properties.setDispatcherEnabled(true);
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var fake = new RagIndexRpcService() {
            public RagIndexStatus submit(RagIndexEvent event, RagAuthEnvelope auth) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    try {
                        if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("first dispatcher was not released");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                    return status(event, RagIndexState.ACCEPTED);
                }
                return status(event, RagIndexState.APPLIED);
            }
            public RagIndexStatus getStatus(RagStatusRequest request, RagAuthEnvelope auth) { fail("unexpected status lookup"); return null; }
        };
        var first = new MemoryRagOutboxDispatcher(f.jdbc, f.projection, f.security, fake, f.properties);
        var second = new MemoryRagOutboxDispatcher(f.jdbc, f.projection, f.security, fake, f.properties);
        var executor = Executors.newSingleThreadExecutor();
        try {
            due(f);
            var delayed = executor.submit(first::dispatch);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            Timestamp expired = Timestamp.from(Instant.now().minusSeconds(1));
            f.jdbc.update("UPDATE memory_rag_outbox SET lease_until=?,next_attempt_at=?", expired, expired);
            second.dispatch();
            assertEquals("APPLIED", state(f));
            releaseFirst.countDown();
            delayed.get(5, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
            assertEquals("APPLIED", state(f));
            assertNull(f.jdbc.queryForObject("SELECT lease_owner FROM memory_rag_outbox", String.class));
            assertNull(f.jdbc.queryForObject("SELECT lease_until FROM memory_rag_outbox", Timestamp.class));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }
    static int eligible(MemoryRagTestSupport f, Instant cutoff) { return f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_rag_outbox WHERE state IN ('PENDING','WAITING') AND next_attempt_at<=? AND attempts<?", Integer.class, Timestamp.from(cutoff), f.properties.getMaxAttempts()); }
    static RagIndexStatus status(RagIndexEvent event, RagIndexState state) { return new RagIndexStatus(1, event.requestId(), event.eventId(), event.spaceId(), event.documentId(), event.revision(), event.contentHash(), state, event.revision(), event.document() == null, null, null, null); }
    static void due(MemoryRagTestSupport f) { f.jdbc.update("UPDATE memory_rag_outbox SET next_attempt_at=?", Timestamp.from(Instant.now().minusSeconds(1))); }
    static String state(MemoryRagTestSupport f) { return f.jdbc.queryForObject("SELECT state FROM memory_rag_outbox", String.class); }
}
