package com.bkanent.rag.index;

import com.bkanent.common.rag.RagCanonicalHash;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagIndexEvent;
import com.bkanent.common.rag.RagIndexOperation;
import com.bkanent.common.rag.RagIndexState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/** No networking implementation: invokes injected storage ports under the database document lock. */
public final class RagIndexWorker implements AutoCloseable {
    public static final int MAX_RETRIES = 5;
    private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(30);
    private final JdbcRagIndexRepository repository;
    private final EnumMap<IndexSink.Backend, IndexSink> sinks = new EnumMap<>(IndexSink.Backend.class);
    private final IndexPayloadCodec codec = new IndexPayloadCodec();
    private final Clock clock;
    private final LongUnaryOperator jitter;
    private final Consumer<RetryExhausted> alert;
    private final java.util.concurrent.ThreadPoolExecutor execution = new java.util.concurrent.ThreadPoolExecutor(
            0, 4, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(),
            runnable -> { Thread thread = new Thread(runnable, "rag-index-sink"); thread.setDaemon(true); return thread; },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    public RagIndexWorker(JdbcRagIndexRepository repository, List<IndexSink> sinks) {
        this(repository, sinks, Clock.systemUTC(), seconds -> java.util.concurrent.ThreadLocalRandom.current().nextLong(251),
                event -> java.util.logging.Logger.getLogger(RagIndexWorker.class.getName())
                        .warning("RAG index retries exhausted: backend=" + event.backend() + ", code=" + event.code()));
    }

    public RagIndexWorker(JdbcRagIndexRepository repository, List<IndexSink> sinks, Clock clock,
                          LongUnaryOperator jitterMillis, Consumer<RetryExhausted> alert) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.jitter = Objects.requireNonNull(jitterMillis);
        this.alert = Objects.requireNonNull(alert);
        for (IndexSink sink : sinks) {
            if (this.sinks.put(sink.backend(), sink) != null) { throw new IllegalArgumentException("duplicate backend"); }
        }
        if (this.sinks.size() != 2) { throw new IllegalArgumentException("keyword and vector sinks required"); }
    }

    /** Scheduling belongs to application wiring; bounded batches avoid an in-memory unbounded queue. */
    public int runBatch(int limit) {
        capacity(limit);
        try {
            List<JdbcRagIndexRepository.DocumentKey> batch = repository.pending(clock.instant(), limit);
            for (JdbcRagIndexRepository.DocumentKey key : batch) { apply(key.spaceId(), key.documentId()); }
            return batch.size();
        } catch (DataAccessException | TransactionException unavailable) { throw RagIndexService.ledgerUnavailable(); }
    }

    public int reconcileBatch(int limit) {
        capacity(limit);
        try {
            List<JdbcRagIndexRepository.DocumentKey> batch = repository.reconciliation(clock.instant(), limit);
            for (JdbcRagIndexRepository.DocumentKey key : batch) { reconcile(key.spaceId(), key.documentId()); }
            return batch.size();
        } catch (DataAccessException | TransactionException unavailable) { throw RagIndexService.ledgerUnavailable(); }
    }

    public void apply(String spaceId, String documentId) { process(spaceId, documentId, false); }
    public void reconcile(String spaceId, String documentId) { process(spaceId, documentId, true); }

    private void process(String space, String id, boolean reconcile) {
        IndexPayloadCodec.identifier(space, 128, "spaceId");
        IndexPayloadCodec.identifier(id, 512, "documentId");
        try {
            List<RetryExhausted> exhausted = repository.committed(() -> {
                Instant now = clock.instant();
                LedgerDocument d = repository.lock(space, id, false, now);
                if (d == null || d.revision == 0 || (!reconcile && d.state == RagIndexState.APPLIED)) { return List.of(); }
                JdbcRagIndexRepository.EventRow row = repository.event(d.eventId)
                        .orElseThrow(RagIndexService::ledgerUnavailable);
                RagIndexEvent event = codec.decode(row.payload());
                if (event.revision() != d.revision || !event.contentHash().equals(d.hash)
                        || !RagCanonicalHash.eventPayloadHash(event).equals(d.payloadHash)) {
                    throw RagIndexService.ledgerUnavailable();
                }
                IndexDocument document = new IndexDocument(event, row.payload());
                if (reconcile && d.state == RagIndexState.APPLIED) {
                    // A later physical mismatch starts a new repair round, not a spent historic retry budget.
                    d.keywordAttempts = d.vectorAttempts = 0;
                    d.keywordRetry = d.vectorRetry = null;
                }
                d.state = RagIndexState.APPLYING;
                repository.save(d, now);
                java.util.ArrayList<RetryExhausted> notifications = new java.util.ArrayList<>();
                applyBackend(d, document, IndexSink.Backend.KEYWORD, reconcile, now, notifications);
                applyBackend(d, document, IndexSink.Backend.VECTOR, reconcile, now, notifications);
                d.state = d.keywordRevision == d.revision && d.vectorRevision == d.revision
                        && d.keywordError == null && d.vectorError == null
                        ? RagIndexState.APPLIED : RagIndexState.RETRY_PENDING;
                d.reconcileAt = now.plus(RECONCILE_INTERVAL);
                repository.save(d, clock.instant());
                repository.markRevision(d);
                return List.copyOf(notifications);
            });
            // Alerts occur after commit; an alert failure cannot roll back persisted failure facts.
            for (RetryExhausted notification : exhausted) {
                try { alert.accept(notification); } catch (RuntimeException ignored) { /* durable RETRY_PENDING remains */ }
            }
        } catch (DataAccessException | TransactionException unavailable) { throw RagIndexService.ledgerUnavailable(); }
    }

    private void applyBackend(LedgerDocument d, IndexDocument document, IndexSink.Backend backend,
                              boolean reconcile, Instant now, List<RetryExhausted> notifications) {
        boolean keyword = backend == IndexSink.Backend.KEYWORD;
        long applied = keyword ? d.keywordRevision : d.vectorRevision;
        int attempts = keyword ? d.keywordAttempts : d.vectorAttempts;
        Instant retry = keyword ? d.keywordRetry : d.vectorRetry;
        if (!reconcile && applied == d.revision) { return; }
        if (attempts > MAX_RETRIES || (retry != null && retry.isAfter(now))) { return; }
        IndexSink sink = sinks.get(backend);
        RagErrorCode error = null;
        boolean success = false;
        String space = document.spaceId();
        String id = document.documentId();
        try {
            success = bounded(() -> {
                Optional<IndexSink.StoredRevision> stored = sink.inspect(space, id);
                if (!matches(document, stored)) {
                    if (document.operation() == RagIndexOperation.DELETE) { sink.delete(document); }
                    else { sink.upsert(document); }
                    stored = sink.inspect(space, id);
                }
                return matches(document, stored);
            });
            if (!success) { error = RagErrorCode.INDEX_CONFLICT; }
        } catch (RagException failure) {
            error = failure.code();
        } catch (RuntimeException failure) {
            // Never persist exception messages: transport exceptions may contain credentials/URLs.
            error = RagErrorCode.DEPENDENCY_UNAVAILABLE;
        }
        if (success) {
            applied = d.revision;
            retry = null;
            error = null;
        } else {
            // An inspect failure also invalidates earlier applied facts during reconciliation.
            applied = 0;
            attempts++;
            boolean permanent = error == RagErrorCode.ENDPOINT_POLICY_DENIED || error == RagErrorCode.FORBIDDEN
                    || error == RagErrorCode.INVALID_ARGUMENT || error == RagErrorCode.AUTHENTICATION_FAILED;
            if (attempts <= MAX_RETRIES && !permanent) {
                long seconds = 1L << (attempts - 1);
                long millis = Math.max(0, Math.min(250, jitter.applyAsLong(seconds)));
                retry = now.plusSeconds(seconds).plusMillis(millis);
            } else {
                retry = null;
                if (permanent) { attempts = MAX_RETRIES + 1; }
                notifications.add(new RetryExhausted(d.spaceId, d.documentId, d.revision, backend, error));
            }
        }
        if (keyword) {
            d.keywordRevision = applied;
            d.keywordAttempts = attempts;
            d.keywordRetry = retry;
            d.keywordError = error;
        } else {
            d.vectorRevision = applied;
            d.vectorAttempts = attempts;
            d.vectorRetry = retry;
            d.vectorError = error;
        }
    }

    private <T> T bounded(java.util.concurrent.Callable<T> action) {
        java.util.concurrent.Future<T> future;
        try { future = execution.submit(action); }
        catch (java.util.concurrent.RejectedExecutionException busy) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try { return future.get(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true);
            throw new RagException(RagErrorCode.TIMEOUT);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RagException(RagErrorCode.TIMEOUT);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RagException rag) { throw rag; }
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override public void close() { execution.shutdownNow(); }

    private static boolean matches(IndexDocument document, Optional<IndexSink.StoredRevision> stored) {
        if (document.operation() == RagIndexOperation.DELETE && stored.isEmpty()) { return true; }
        return stored.map(value -> value.revision() == document.revision()
                && Objects.equals(value.contentHash(), document.contentHash())
                && value.tombstone() == (document.operation() == RagIndexOperation.DELETE)).orElse(false);
    }

    private static void capacity(int limit) {
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("batch limit must be 1..100"); }
    }

    public record RetryExhausted(String spaceId, String documentId, long revision,
                                 IndexSink.Backend backend, RagErrorCode code) { }
}
