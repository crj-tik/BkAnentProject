package com.bkanent.rag.index;

import com.bkanent.common.rag.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionException;

/** Internal lifecycle API. RPC MUST authorize submit/status/replay before invoking this service. */
public final class RagIndexService {
    private final JdbcRagIndexRepository repository;
    private final IndexPayloadCodec codec = new IndexPayloadCodec();
    private final Clock clock;

    public RagIndexService(JdbcRagIndexRepository repository) { this(repository, Clock.systemUTC()); }
    public RagIndexService(JdbcRagIndexRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Returns only after its own JDBC transaction commits. No sinks or other external calls. */
    public RagIndexStatus submit(RagIndexEvent event) {
        String payload = codec.encode(event);
        String identity = RagCanonicalHash.eventPayloadHash(event);
        try {
            return repository.committed(() -> accept(event, payload, identity));
        } catch (DuplicateKeyException race) {
            // Global event-id race across different document locks. Roll back before rereading winner.
            return database(() -> repository.committed(() -> accept(event, payload, identity)));
        } catch (DataAccessException | TransactionException unavailable) {
            throw ledgerUnavailable();
        }
    }

    private RagIndexStatus accept(RagIndexEvent event, String payload, String identity) {
        Instant now = clock.instant();
        LedgerDocument d = repository.lock(event.spaceId(), event.documentId(), true, now);
        Optional<JdbcRagIndexRepository.EventRow> existing = repository.event(event.eventId());
        if (existing.isPresent()) {
            JdbcRagIndexRepository.EventRow old = existing.get();
            if (!old.spaceId().equals(event.spaceId()) || !old.documentId().equals(event.documentId())
                    || old.revision() != event.revision() || !old.payloadHash().equals(identity)) {
                return incomingStatus(event, RagIndexState.CONFLICT, d, RagErrorCode.INDEX_CONFLICT);
            }
            return status(event.requestId(), old, d);
        }
        RagIndexState state = RagIndexState.ACCEPTED;
        RagErrorCode error = null;
        Optional<JdbcRagIndexRepository.EventRow> knownRevision = repository.revision(
                event.spaceId(), event.documentId(), event.revision());
        if (knownRevision.isPresent() && !identity.equals(knownRevision.get().payloadHash())) {
            state = RagIndexState.CONFLICT;
            error = RagErrorCode.INDEX_CONFLICT;
        } else if (event.revision() < d.revision) {
            state = RagIndexState.STALE;
        } else if (event.revision() == d.revision && !identity.equals(d.payloadHash)) {
            state = RagIndexState.CONFLICT;
            error = RagErrorCode.INDEX_CONFLICT;
        } else if (event.revision() == d.revision) {
            state = d.state;
        } else {
            d.revision = event.revision();
            d.hash = event.contentHash();
            d.payloadHash = identity;
            d.eventId = event.eventId();
            d.schemaVersion = event.schemaVersion();
            d.tombstone = event.operation() == RagIndexOperation.DELETE;
            d.state = RagIndexState.ACCEPTED;
            d.keywordAttempts = d.vectorAttempts = 0;
            d.keywordError = d.vectorError = null;
            d.keywordRetry = d.vectorRetry = null;
            d.reconcileAt = null;
            repository.save(d, now);
            repository.staleOlder(d);
        }
        JdbcRagIndexRepository.EventRow row = new JdbcRagIndexRepository.EventRow(event.eventId(),
                event.spaceId(), event.documentId(), event.revision(), event.contentHash(), identity,
                event.schemaVersion(), event.operation().name(), payload, state, error);
        repository.insert(row, event.createdAt(), now);
        return status(event.requestId(), row, d);
    }

    public RagIndexStatus getStatus(RagStatusRequest request) {
        selector(request.contractVersion(), request.spaceId(), request.eventId(), request.documentId());
        return database(() -> {
            JdbcRagIndexRepository.EventRow event = selected(request.spaceId(), request.eventId(), request.documentId());
            LedgerDocument d = repository.document(event.spaceId(), event.documentId()).orElseThrow(RagIndexService::ledgerUnavailable);
            return status(request.requestId(), event, d);
        });
    }

    /** Internal admin operation; authorization is deliberately owned by RPC, never by caller text. */
    public RagIndexStatus replay(RagReplayRequest request) {
        selector(request.contractVersion(), request.spaceId(), request.eventId(), request.documentId());
        return database(() -> repository.committed(() -> {
            JdbcRagIndexRepository.EventRow event = selected(request.spaceId(), request.eventId(), request.documentId());
            LedgerDocument d = repository.lock(event.spaceId(), event.documentId(), false, clock.instant());
            if (d == null) { throw ledgerUnavailable(); }
            if (event.state() == RagIndexState.CONFLICT || event.revision() != d.revision
                    || !event.payloadHash().equals(d.payloadHash)) {
                return status(request.requestId(), event, d);
            }
            // Replay re-verifies BOTH actual backends; acknowledgements may be invalidated by late writes.
            d.keywordRevision = d.vectorRevision = 0;
            d.keywordAttempts = d.vectorAttempts = 0;
            d.keywordError = d.vectorError = null;
            d.keywordRetry = d.vectorRetry = null;
            d.state = RagIndexState.ACCEPTED;
            repository.save(d, clock.instant());
            repository.markRevision(d);
            return status(request.requestId(), event, d);
        }));
    }

    /** Call separately on each recall hit BEFORE fusion/dedup/rerank. Missing hashes fail closed. */
    public boolean visible(String spaceId, RagCandidate candidate) {
        IndexPayloadCodec.identifier(spaceId, 128, "spaceId");
        if (candidate == null) { return false; }
        return database(() -> repository.document(spaceId, candidate.documentId()).map(d -> {
            RagValue hash = candidate.metadata() == null ? null : candidate.metadata().get("contentHash");
            return d.state == RagIndexState.APPLIED && !d.tombstone && d.revision > 0
                    && d.keywordRevision == d.revision && d.vectorRevision == d.revision
                    && candidate.projectionVersion() == d.revision
                    && Objects.equals(candidate.schemaVersion(), d.schemaVersion)
                    && hash != null && hash.type() == RagValueType.STRING && Objects.equals(hash.scalar(), d.hash);
        }).orElse(false));
    }

    private JdbcRagIndexRepository.EventRow selected(String space, String eventId, String documentId) {
        if (eventId == null) {
            LedgerDocument d = repository.document(space, documentId)
                    .orElseThrow(() -> new RagException(RagErrorCode.NOT_FOUND));
            eventId = d.eventId;
        }
        JdbcRagIndexRepository.EventRow event = repository.event(eventId)
                .orElseThrow(() -> new RagException(RagErrorCode.NOT_FOUND));
        if (!space.equals(event.spaceId())) { throw new RagException(RagErrorCode.NOT_FOUND); }
        return event;
    }

    private static void selector(int version, String space, String eventId, String documentId) {
        if (version != 1 || (eventId == null) == (documentId == null)) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "selector");
        }
        IndexPayloadCodec.identifier(space, 128, "spaceId");
        IndexPayloadCodec.identifier(eventId != null ? eventId : documentId, eventId != null ? 128 : 512, "selector");
    }

    private static RagIndexStatus incomingStatus(RagIndexEvent e, RagIndexState state, LedgerDocument d, RagErrorCode error) {
        return new RagIndexStatus(1, e.requestId(), e.eventId(), e.spaceId(), e.documentId(), e.revision(),
                e.contentHash(), state, d.revision, d.tombstone, keyword(d), vector(d), error);
    }

    static RagIndexStatus status(String request, JdbcRagIndexRepository.EventRow e, LedgerDocument d) {
        RagIndexState state = e.state();
        if (state != RagIndexState.CONFLICT) {
            state = e.revision() < d.revision ? RagIndexState.STALE : d.state;
        }
        RagErrorCode error = state == RagIndexState.CONFLICT ? RagErrorCode.INDEX_CONFLICT
                : state == RagIndexState.RETRY_PENDING ? (d.keywordError != null ? d.keywordError : d.vectorError) : null;
        return new RagIndexStatus(1, request, e.eventId(), e.spaceId(), e.documentId(), e.revision(), e.hash(),
                state, d.revision, d.tombstone, keyword(d), vector(d), error);
    }

    private static RagBackendStatus keyword(LedgerDocument d) {
        return new RagBackendStatus(d.keywordRevision, d.keywordAttempts, d.keywordError, d.keywordRetry);
    }
    private static RagBackendStatus vector(LedgerDocument d) {
        return new RagBackendStatus(d.vectorRevision, d.vectorAttempts, d.vectorError, d.vectorRetry);
    }
    private static <T> T database(java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch (DataAccessException | TransactionException unavailable) { throw ledgerUnavailable(); }
    }
    static RagException ledgerUnavailable() { return new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "ledger"); }
}
