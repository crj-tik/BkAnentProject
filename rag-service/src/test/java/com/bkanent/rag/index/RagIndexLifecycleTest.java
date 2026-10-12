package com.bkanent.rag.index;

import com.bkanent.common.rag.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class RagIndexLifecycleTest {
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcRagIndexRepository repository;
    private RagIndexService service;
    private RagIndexWorker worker;
    private MutableClock clock;
    private FakeSink keyword;
    private FakeSink vector;
    private final List<RagIndexWorker.RetryExhausted> alerts = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:rag_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(dataSource);
        // Execute the production table DDL twice. H2 has no CREATE DATABASE/USE or ENGINE clause.
        Path migration = Path.of("../sql/migrations/20261012_rag_control.sql");
        if (!Files.exists(migration)) { migration = Path.of("sql/migrations/20261012_rag_control.sql"); }
        String sql = Files.readString(migration).replaceAll("(?m)^--.*$", "")
                .replaceAll("CREATE DATABASE[^;]*;", "").replaceAll("USE bk_rag;", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
        for (int repeat = 0; repeat < 2; repeat++) {
            for (String statement : sql.split(";")) { if (!statement.isBlank()) { jdbc.execute(statement); } }
        }
        clock = new MutableClock();
        repository = new JdbcRagIndexRepository(dataSource);
        service = new RagIndexService(repository, clock);
        keyword = new FakeSink(IndexSink.Backend.KEYWORD);
        vector = new FakeSink(IndexSink.Backend.VECTOR);
        worker = new RagIndexWorker(repository, List.of(keyword, vector), clock, seconds -> 0, alerts::add);
    }

    @AfterEach void closeWorker() { worker.close(); }

    @Test
    void submitIsDurableAfterCommitAndHasNoSinkCalls() {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "new text");
        assertEquals(RagIndexState.ACCEPTED, service.submit(e).state());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rag_index_event", Integer.class));
        assertEquals(0, keyword.writes.get());
        assertFalse(service.visible(e.spaceId(), candidate(e)));
        worker.runBatch(10);
        assertEquals(RagIndexState.APPLIED, status(e).state());
        assertTrue(service.visible(e.spaceId(), candidate(e)));
    }

    @Test
    void repositoryTransactionRollbackLeavesNoEventOrDocument() {
        RagIndexEvent e = upsert("rollback", "listing:1", 1, "never committed");
        assertThrows(IllegalStateException.class, () -> repository.committed(() -> {
            repository.lock(e.spaceId(), e.documentId(), true, clock.instant());
            repository.insert(new JdbcRagIndexRepository.EventRow(e.eventId(), e.spaceId(), e.documentId(),
                    e.revision(), e.contentHash(), RagCanonicalHash.eventPayloadHash(e), e.schemaVersion(),
                    e.operation().name(), new IndexPayloadCodec().encode(e), RagIndexState.ACCEPTED, null),
                    e.createdAt(), clock.instant());
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rag_index_event", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rag_document_state", Integer.class));
    }

    @Test
    void submitAcknowledgementIsCommittedIndependentlyOfCallerTransaction() {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "already-domain-committed");
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(tx -> {
            assertEquals(RagIndexState.ACCEPTED, service.submit(e).state());
            tx.setRollbackOnly();
            return null;
        });
        assertEquals(RagIndexState.ACCEPTED, status(e).state());
    }

    @Test
    void transportRetryAndSameRevisionDifferentEventsAreIdempotent() {
        RagIndexEvent e = upsert("e1", "listing:1", 3, "stable");
        service.submit(e);
        RagIndexEvent retry = new RagIndexEvent(1, "new-request", e.eventId(), e.spaceId(), e.documentId(),
                e.revision(), e.schemaVersion(), e.operation(), e.contentHash(), e.document(), clock.instant().plusSeconds(1));
        service.submit(retry);
        service.submit(upsert("e2", "listing:1", 3, "stable"));
        worker.apply(e.spaceId(), e.documentId());
        assertEquals(RagIndexState.APPLIED, status(e).state());
        assertEquals(RagIndexState.APPLIED, service.getStatus(new RagStatusRequest(1,"s",e.spaceId(),"e2",null)).state());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM rag_index_event", Integer.class));
        assertEquals(1, keyword.writes.get());
    }

    @Test
    void sameRevisionDifferentPayloadConflictsAndOldRevisionIsStale() {
        RagIndexEvent e = upsert("e3", "listing:1", 3, "latest");
        service.submit(e);
        RagIndexEvent conflict = upsert("bad", "listing:1", 3, "different");
        assertEquals(RagIndexState.CONFLICT, service.submit(conflict).state());
        assertEquals(RagIndexState.CONFLICT, status(conflict).state());
        assertEquals(RagIndexState.STALE, service.submit(upsert("old", "listing:1", 2, "old")).state());
        worker.runBatch(10);
        assertTrue(service.visible(e.spaceId(), candidate(e)));
        assertEquals(RagIndexState.CONFLICT, status(conflict).state());
    }

    @Test
    void historicalRevisionCannotAcquireDifferentPayloadAfterNewerAccept() {
        service.submit(upsert("e1", "listing:1", 1, "original"));
        service.submit(upsert("e2", "listing:1", 2, "newer"));
        assertEquals(RagIndexState.CONFLICT, service.submit(upsert("bad-old", "listing:1", 1, "different")).state());
        assertEquals(RagIndexState.STALE, service.submit(upsert("same-old", "listing:1", 1, "original")).state());
    }

    @Test
    void reusedEventIdOnAnotherDocumentConflictsWithoutExposingOtherIdentity() {
        service.submit(upsert("global", "listing:1", 1, "first"));
        RagIndexEvent second = upsert("global", "listing:2", 1, "second");
        RagIndexStatus result = service.submit(second);
        assertEquals(RagIndexState.CONFLICT, result.state());
        assertEquals("listing:2", result.documentId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rag_index_event", Integer.class));
    }

    @Test
    void acceptedDeleteHidesImmediatelyAndOldUpdateCannotResurrect() {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "old");
        service.submit(e); worker.runBatch(10);
        RagIndexEvent deletion = deletion("d2", e.documentId(), 2);
        assertEquals(RagIndexState.ACCEPTED, service.submit(deletion).state());
        assertFalse(service.visible(e.spaceId(), candidate(e)));
        assertEquals(RagIndexState.STALE, service.submit(upsert("late1", e.documentId(), 1, "old")).state());
        worker.runBatch(10);
        assertEquals(RagIndexState.APPLIED, status(deletion).state());
        assertTrue(status(deletion).tombstone());
        assertTrue(vector.inspect(e.spaceId(), e.documentId()).isEmpty());
        vector.put(e); // A network write from the old connection arrives after deletion.
        assertFalse(service.visible(e.spaceId(), candidate(e)));
        worker.reconcile(e.spaceId(), e.documentId());
        assertTrue(vector.inspect(e.spaceId(), e.documentId()).isEmpty());
    }

    @Test
    void failedDeleteAndInspectReconciliationNeverExposeDocument() {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "old");
        service.submit(e); worker.runBatch(10);
        RagIndexEvent delete = deletion("d2",e.documentId(),2);
        service.submit(delete); vector.fail = true; worker.runBatch(10);
        assertEquals(RagIndexState.RETRY_PENDING,status(delete).state());
        assertEquals(2,status(delete).keyword().appliedRevision());
        assertFalse(service.visible(e.spaceId(),candidate(e)));
        assertFalse(jdbc.queryForObject("SELECT vector_error FROM rag_document_state WHERE document_id=?",String.class,e.documentId()).contains("secret"));
        vector.fail = false; clock.advance(1); worker.runBatch(10);
        assertEquals(RagIndexState.APPLIED,status(delete).state());
        RagIndexEvent fresh = upsert("e3",e.documentId(),3,"fresh");
        service.submit(fresh); worker.runBatch(10);
        vector.inspectFail = true; worker.reconcile(fresh.spaceId(),fresh.documentId());
        assertEquals(RagIndexState.RETRY_PENDING,status(fresh).state());
        assertFalse(service.visible(fresh.spaceId(),candidate(fresh)));
        vector.inspectFail = false; clock.advance(1); worker.runBatch(10);
        assertTrue(service.visible(fresh.spaceId(),candidate(fresh)));
    }

    @Test
    void partialWritesAreHiddenAndOnlyFailedBackendIsRetried() {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "latest");
        service.submit(e); vector.fail = true;
        worker.runBatch(10);
        RagIndexStatus partial = status(e);
        assertEquals(RagIndexState.RETRY_PENDING, partial.state());
        assertEquals(1, partial.keyword().appliedRevision());
        assertEquals(0, partial.vector().appliedRevision());
        assertFalse(service.visible(e.spaceId(), candidate(e)));
        vector.fail = false; clock.advance(1); worker.runBatch(10);
        assertEquals(RagIndexState.APPLIED, status(e).state());
        assertEquals(1, keyword.writes.get());
        assertTrue(service.visible(e.spaceId(), candidate(e)));
    }

    @Test
    void fiveRetriesBackoffExhaustionAndAuthorizedBoundaryReplay() {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "latest");
        service.submit(e); vector.fail = true;
        long[] delays = {1, 2, 4, 8, 16};
        worker.runBatch(10);
        for (long delay : delays) {
            assertEquals(clock.instant().plusSeconds(delay), status(e).vector().nextRetryAt());
            assertEquals(0, worker.runBatch(10));
            clock.advance(delay); worker.runBatch(10);
        }
        assertEquals(6, status(e).vector().attempts()); // initial attempt plus exactly five retries
        assertNull(status(e).vector().nextRetryAt());
        assertEquals(RagIndexState.RETRY_PENDING, status(e).state());
        assertEquals(1, alerts.size());
        assertEquals(0, worker.runBatch(10));
        vector.fail = false;
        assertEquals(RagIndexState.ACCEPTED, service.replay(new RagReplayRequest(1,"admin",e.spaceId(),e.eventId(),null)).state());
        worker.runBatch(10);
        assertEquals(RagIndexState.APPLIED, status(e).state());
    }

    @Test
    void delayedOldPhysicalWriteDoesNotExposeOldBodyAndActualHashReconciles() {
        RagIndexEvent old = upsert("e1", "listing:1", 1, "old body");
        service.submit(old); worker.runBatch(10);
        RagIndexEvent latest = upsert("e2", "listing:1", 2, "new body");
        service.submit(latest); worker.runBatch(10);
        vector.put(old);
        assertFalse(service.visible(old.spaceId(), candidate(old)));
        assertTrue(service.visible(latest.spaceId(), candidate(latest)));
        worker.reconcile(latest.spaceId(), latest.documentId());
        assertEquals(latest.contentHash(), vector.inspect(latest.spaceId(), latest.documentId()).orElseThrow().contentHash());
        // Revision alone is insufficient; a forged new revision over old hash is hidden.
        assertFalse(service.visible(latest.spaceId(), candidate(old, latest.revision(), old.contentHash(), latest.schemaVersion())));
        assertFalse(service.visible(latest.spaceId(), candidate(latest, latest.revision(), null, latest.schemaVersion())));
        assertFalse(service.visible(latest.spaceId(), candidate(latest, latest.revision(), latest.contentHash(), "other-schema")));
    }

    @Test
    void timeoutCannotCancelLateNetworkWriteButLedgerHidesOldProjection() throws Exception {
        worker.close();
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch oldArrived = new CountDownLatch(1);
        IndexSink lateVector = new IndexSink() {
            @Override public Backend backend() { return Backend.VECTOR; }
            @Override public void upsert(IndexDocument document) {
                if (document.revision() == 1) {
                    oldEntered.countDown();
                    boolean released = false;
                    while (!released) {
                        try { released = releaseOld.await(10, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { /* network cancellation is not guaranteed */ }
                    }
                }
                vector.put(document.event());
                if (document.revision() == 1) { oldArrived.countDown(); }
            }
            @Override public void delete(IndexDocument document) { vector.delete(document); }
            @Override public Optional<StoredRevision> inspect(String space, String id) { return vector.inspect(space,id); }
        };
        worker = new RagIndexWorker(repository,List.of(keyword,lateVector),clock,seconds -> 0,alerts::add);
        RagIndexEvent old = upsert("slow1", "listing:1", 1, "old network body");
        try {
            service.submit(old);
            long started = System.nanoTime();
            worker.runBatch(10);
            assertTrue(oldEntered.await(1,TimeUnit.SECONDS));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started) < 8000);
            assertEquals(RagErrorCode.TIMEOUT,status(old).vector().errorCode());
            RagIndexEvent latest = upsert("new2", "listing:1", 2, "new body");
            service.submit(latest); worker.runBatch(10);
            assertEquals(RagIndexState.APPLIED,status(latest).state());
            releaseOld.countDown(); assertTrue(oldArrived.await(2,TimeUnit.SECONDS));
            assertFalse(service.visible(old.spaceId(),candidate(old)));
            assertFalse(service.visible(old.spaceId(),candidate(old,2,old.contentHash(),"v1")));
            worker.reconcile(latest.spaceId(),latest.documentId());
            assertEquals(2,vector.inspect(latest.spaceId(),latest.documentId()).orElseThrow().revision());
        } finally { releaseOld.countDown(); }
    }

    @Test
    void periodicReconciliationDetectsHashMismatchAndReplaysPersistedPayload() {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "persisted");
        service.submit(e); worker.runBatch(10);
        vector.put(upsert("other", "listing:1", 1, "wrong actual hash"));
        assertEquals(0, worker.reconcileBatch(10));
        clock.advance(30);
        assertEquals(1, worker.reconcileBatch(10));
        assertEquals(e.contentHash(), vector.inspect(e.spaceId(), e.documentId()).orElseThrow().contentHash());
        assertEquals(RagIndexState.APPLIED, status(e).state());
    }

    @Test
    void reconciliationStartsFreshBoundedRetryRoundAfterSuccessfulRecovery() {
        RagIndexEvent e = upsert("e1","listing:1",1,"stable");
        service.submit(e); vector.fail = true; worker.runBatch(10);
        for (long delay : new long[]{1,2,4,8}) { clock.advance(delay); worker.runBatch(10); }
        vector.fail = false; clock.advance(16); worker.runBatch(10);
        assertEquals(RagIndexState.APPLIED,status(e).state());
        vector.inspectFail = true;
        worker.reconcile(e.spaceId(),e.documentId());
        assertEquals(1,status(e).vector().attempts());
        assertEquals(clock.instant().plusSeconds(1),status(e).vector().nextRetryAt());
        assertTrue(alerts.isEmpty());
    }

    @Test
    void visibilityAndSubmissionFailClosedWhenLedgerUnavailable() {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "latest");
        service.submit(e); worker.runBatch(10);
        jdbc.execute("DROP TABLE rag_document_state");
        RagException failure = assertThrows(RagException.class, () -> service.visible(e.spaceId(), candidate(e)));
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, failure.code());
        assertEquals("ledger", failure.field());
        assertThrows(RagException.class, () -> service.submit(upsert("e2", "listing:1", 2, "new")));
    }

    @Test
    void parameterBindingAcceptsQuotesAndUnicodeWithoutSqlInjection() {
        String document = "listing:' OR 1=1 -- 北京\\甲";
        RagIndexEvent e = upsert("quoted'event", document, 1, "literal'; DROP TABLE rag_index_event; 中文");
        service.submit(e); worker.runBatch(10);
        assertEquals(RagIndexState.APPLIED, status(e).state());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rag_index_event", Integer.class));
        assertEquals(document, service.getStatus(new RagStatusRequest(1,"s",e.spaceId(),null,document)).documentId());
        String payload = jdbc.queryForObject("SELECT canonical_payload FROM rag_index_event WHERE event_id=?", String.class,e.eventId());
        assertTrue(payload.contains("DROP TABLE"));
        assertFalse(payload.contains("accessToken"));
        assertFalse(payload.contains("authentication"));
    }

    @Test
    void normalWorkersAndSubmitSerializeOnSameDocumentRow() throws Exception {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "old");
        service.submit(e);
        keyword.entered = new CountDownLatch(1); keyword.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var first = pool.submit(() -> worker.apply(e.spaceId(), e.documentId()));
            assertTrue(keyword.entered.await(2, TimeUnit.SECONDS));
            var second = pool.submit(() -> worker.apply(e.spaceId(), e.documentId()));
            RagIndexEvent newer = upsert("e2", "listing:1", 2, "new");
            var submit = pool.submit(() -> service.submit(newer));
            Thread.sleep(100);
            assertFalse(second.isDone()); assertFalse(submit.isDone());
            keyword.release.countDown();
            first.get(3, TimeUnit.SECONDS); second.get(3, TimeUnit.SECONDS); submit.get(3, TimeUnit.SECONDS);
            worker.runBatch(10);
            assertEquals(1, keyword.maxActive.get());
            assertEquals(RagIndexState.APPLIED, status(newer).state());
            assertEquals(RagIndexState.STALE, status(e).state());
            assertFalse(service.visible(e.spaceId(), candidate(e)));
        } finally { keyword.release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void concurrentSameEventSubmissionsCreateOneDurableEvent() throws Exception {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "same");
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<java.util.concurrent.Future<RagIndexStatus>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) { results.add(pool.submit(() -> service.submit(e))); }
            for (var result : results) { assertEquals(RagIndexState.ACCEPTED, result.get(3, TimeUnit.SECONDS).state()); }
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM rag_index_event", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void crashedWorkerWriteWithoutLedgerCommitIsRecoveredByActualInspect() {
        RagIndexEvent e = upsert("e1", "listing:1", 1, "crash-recovery");
        service.submit(e);
        keyword.put(e); // physical side effect occurred, connection died before ledger commit
        worker.close();
        worker = new RagIndexWorker(repository,List.of(keyword,vector),clock,seconds -> 0,alerts::add);
        worker.runBatch(10);
        assertEquals(0, keyword.writes.get());
        assertEquals(RagIndexState.APPLIED, status(e).state());
    }

    @Test
    void staleAndConflictingReplayNeverRewindsCurrentRevision() {
        RagIndexEvent old = upsert("e1", "listing:1", 1, "old");
        service.submit(old);
        RagIndexEvent latest = upsert("e2", "listing:1", 2, "new");
        service.submit(latest); worker.runBatch(10);
        assertEquals(RagIndexState.STALE, service.replay(new RagReplayRequest(1,"admin",old.spaceId(),old.eventId(),null)).state());
        RagIndexEvent conflict = upsert("bad", "listing:1", 2, "bad");
        service.submit(conflict);
        assertEquals(RagIndexState.CONFLICT, service.replay(new RagReplayRequest(1,"admin",conflict.spaceId(),conflict.eventId(),null)).state());
        assertTrue(service.visible(latest.spaceId(),candidate(latest)));
    }

    private RagIndexStatus status(RagIndexEvent e) {
        return service.getStatus(new RagStatusRequest(1,"status",e.spaceId(),e.eventId(),null));
    }
    private RagIndexEvent upsert(String eventId, String documentId, long revision, String content) {
        RagDocument document = new RagDocument("listing", documentId, content,
                Map.of("active",RagValue.bool(true),"ownerId",RagValue.string("123")));
        return new RagIndexEvent(1,"request",eventId,"listing.knowledge",documentId,revision,"v1",RagIndexOperation.UPSERT,
                RagCanonicalHash.documentHash(RagIndexOperation.UPSERT,document),document,clock.instant());
    }
    private RagIndexEvent deletion(String eventId, String documentId, long revision) {
        return new RagIndexEvent(1,"delete",eventId,"listing.knowledge",documentId,revision,"v1",RagIndexOperation.DELETE,
                RagCanonicalHash.documentHash(RagIndexOperation.DELETE,null),null,clock.instant());
    }
    private RagCandidate candidate(RagIndexEvent e) { return candidate(e,e.revision(),e.contentHash(),e.schemaVersion()); }
    private RagCandidate candidate(RagIndexEvent e,long revision,String hash,String schema) {
        return new RagCandidate(e.documentId(),"listing",e.documentId(),e.document().content(),revision,schema,
                List.of(RagRecallMode.VECTOR),null,1.0,null,null,null,null,null,
                hash == null ? Map.of() : Map.of("contentHash",RagValue.string(hash)));
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-12T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FakeSink implements IndexSink {
        final Backend backend;
        final Map<String,StoredRevision> stored = new ConcurrentHashMap<>();
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        volatile boolean fail;
        volatile boolean inspectFail;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        FakeSink(Backend backend) { this.backend = backend; }
        @Override public Backend backend() { return backend; }
        @Override public void upsert(IndexDocument document) {
            int concurrent = active.incrementAndGet(); maxActive.accumulateAndGet(concurrent,Math::max);
            try {
                if (entered != null) { entered.countDown(); }
                if (release != null && !release.await(3,TimeUnit.SECONDS)) { throw new IllegalStateException("blocked"); }
                if (fail) { throw new IllegalStateException("secret-password-must-not-persist"); }
                writes.incrementAndGet(); put(document.event());
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            finally { active.decrementAndGet(); }
        }
        @Override public void delete(IndexDocument document) {
            if (fail) { throw new IllegalStateException("secret-password-must-not-persist"); }
            writes.incrementAndGet(); stored.remove(document.spaceId()+"/"+document.documentId());
        }
        @Override public Optional<StoredRevision> inspect(String space,String id) {
            if (inspectFail) { throw new IllegalStateException("secret-transport-inspection"); }
            return Optional.ofNullable(stored.get(space+"/"+id));
        }
        void put(RagIndexEvent e) { stored.put(e.spaceId()+"/"+e.documentId(),new StoredRevision(e.revision(),e.contentHash(),false)); }
    }
}
