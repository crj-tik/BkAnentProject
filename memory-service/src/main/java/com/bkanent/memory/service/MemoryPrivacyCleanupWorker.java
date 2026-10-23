package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.bkanent.memory.service.MemoryArchiveStore.*;

/** At-least-once cleanup of exact source identities; external cleaners must be idempotent. */
@Service
public class MemoryPrivacyCleanupWorker {
    private record Claim(String jobId, String contextId, String sourceEventId, String sourceHash,
                         String leaseOwner, int attempt, MemoryContextKey contextKey, boolean exhausted) { }

    private final MemoryArchiveStore store;
    private final List<MemoryPrivacyProjectionCleaner> cleaners;
    private final int batchSize;
    private final int maxAttempts;
    private final int leaseSeconds;
    private final int retrySeconds;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public MemoryPrivacyCleanupWorker(MemoryArchiveStore store, PlatformTransactionManager manager,
                                      List<MemoryPrivacyProjectionCleaner> cleaners,
                                      @Value("${memory.v2.privacy-cleanup.batch-size:8}") int batchSize,
                                      @Value("${memory.v2.privacy-cleanup.max-attempts:8}") int maxAttempts,
                                      @Value("${memory.v2.privacy-cleanup.lease-seconds:60}") int leaseSeconds,
                                      @Value("${memory.v2.privacy-cleanup.retry-seconds:15}") int retrySeconds) {
        this(store, manager, cleaners, batchSize, maxAttempts, leaseSeconds, retrySeconds, Clock.systemUTC());
    }

    MemoryPrivacyCleanupWorker(MemoryArchiveStore store, PlatformTransactionManager manager,
                               List<MemoryPrivacyProjectionCleaner> cleaners,
                               int batchSize, int maxAttempts, int leaseSeconds, int retrySeconds, Clock clock) {
        this.store = store;
        this.cleaners = List.copyOf(cleaners);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
        this.retrySeconds = retrySeconds;
        this.clock = clock;
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || leaseSeconds < 1 || retrySeconds < 1) {
            throw new IllegalArgumentException("invalid privacy cleanup limits");
        }
        if (this.cleaners.size() > 32 || this.cleaners.stream().map(MemoryPrivacyProjectionCleaner::projectionId).distinct().count() != this.cleaners.size()
                || this.cleaners.stream().anyMatch(cleaner -> cleaner.projectionId() == null
                || !cleaner.projectionId().matches("[A-Za-z0-9][A-Za-z0-9.-]{0,127}"))) {
            throw new IllegalArgumentException("invalid privacy projection registration");
        }
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    @Scheduled(fixedDelayString = "${memory.v2.privacy-cleanup.poll-ms:5000}")
    public void scheduledRun() { runOnce(); }

    public void runOnce() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (int i = 0; i < batchSize; i++) {
                Claim claim = transaction(this::claim);
                if (claim == null) return;
                if (claim.exhausted()) continue;
                try { clean(claim); }
                catch (RuntimeException exception) { fail(claim, error(exception)); }
            }
        } finally { running.set(false); }
    }

    private Claim claim() {
        LocalDateTime now = now();
        var row = store.one("SELECT j.job_id,j.context_id,j.source_event_id,j.attempts,d.source_hash,d.action_kind,"
                        + "c.owner_id,c.domain_id,c.agent_id,c.conversation_id FROM memory_processing_job j "
                        + "JOIN memory_privacy_deletion d ON d.context_id=j.context_id AND d.source_event_id=j.source_event_id "
                        + "JOIN memory_conversation c ON c.context_id=j.context_id WHERE j.job_kind='PRIVACY_CLEANUP' "
                        + "AND d.cleanup_state IN ('PENDING','PROCESSING','RETRY') AND "
                        + "((j.state IN ('PENDING','RETRY') AND j.available_at<=?) OR (j.state='PROCESSING' AND j.lease_until<=?)) "
                        + "ORDER BY j.available_at,j.job_id LIMIT 1 FOR UPDATE SKIP LOCKED", now, now);
        if (row == null) return null;
        String jobId = (String) row.get("job_id");
        String contextId = (String) row.get("context_id");
        String sourceEventId = (String) row.get("source_event_id");
        int attempt = (int) number(row, "attempts");
        if (attempt >= maxAttempts) {
            store.jdbc.update("UPDATE memory_processing_job SET state='FAILED',last_error='RETRY_EXHAUSTED',lease_owner=NULL,lease_until=NULL WHERE job_id=?", jobId);
            store.jdbc.update("UPDATE memory_privacy_deletion SET cleanup_state='FAILED',last_error='RETRY_EXHAUSTED',updated_at=? WHERE context_id=? AND source_event_id=?",
                    now, contextId, sourceEventId);
            return new Claim(jobId, contextId, sourceEventId, (String) row.get("source_hash"), null, attempt,
                    context(row), true);
        }
        String lease = UUID.randomUUID().toString();
        int nextAttempt = attempt + 1;
        store.jdbc.update("UPDATE memory_processing_job SET state='PROCESSING',attempts=?,lease_owner=?,lease_until=?,last_error=NULL WHERE job_id=?",
                nextAttempt, lease, now.plusSeconds(leaseSeconds), jobId);
        store.jdbc.update("UPDATE memory_privacy_deletion SET cleanup_state='PROCESSING',attempts=?,last_error=NULL,updated_at=?,completed_at=NULL "
                        + "WHERE context_id=? AND source_event_id=?", nextAttempt, now, contextId, sourceEventId);
        return new Claim(jobId, contextId, sourceEventId, (String) row.get("source_hash"), lease, nextAttempt, context(row), false);
    }

    private void clean(Claim claim) {
        transaction(() -> {
            verifyLease(claim);
            LocalDateTime now = now();
            for (var cleaner : cleaners) {
                store.jdbc.update("INSERT INTO memory_privacy_cleanup_target(context_id,source_event_id,projection_id,cleanup_state,updated_at) "
                                + "VALUES(?,?,?,'PENDING',?) ON DUPLICATE KEY UPDATE projection_id=projection_id",
                        claim.contextId(), claim.sourceEventId(), cleaner.projectionId(), now);
            }
            return null;
        });
        List<String> targets = transaction(() -> store.jdbc.queryForList("SELECT projection_id FROM memory_privacy_cleanup_target "
                + "WHERE context_id=? AND source_event_id=? AND cleanup_state<>'COMPLETE' ORDER BY projection_id",
                String.class, claim.contextId(), claim.sourceEventId()));
        Map<String, MemoryPrivacyProjectionCleaner> byId = new HashMap<>();
        cleaners.forEach(cleaner -> byId.put(cleaner.projectionId(), cleaner));
        for (String target : targets) {
            MemoryPrivacyProjectionCleaner cleaner = byId.get(target);
            if (cleaner == null) throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
            transaction(() -> { verifyLease(claim); return null; });
            cleaner.remove(claim.contextKey(), claim.sourceEventId(), claim.sourceHash());
            transaction(() -> {
                verifyLease(claim);
                int updated = store.jdbc.update("UPDATE memory_privacy_cleanup_target SET cleanup_state='COMPLETE',last_error=NULL,updated_at=? "
                                + "WHERE context_id=? AND source_event_id=? AND projection_id=?",
                        now(), claim.contextId(), claim.sourceEventId(), target);
                require(updated == 1, MemoryErrorCode.VERSION_CONFLICT);
                return null;
            });
        }
        transaction(() -> { finish(claim); return null; });
    }

    private void finish(Claim claim) {
        verifyLease(claim);
        var deletion = store.one("SELECT action_kind,source_hash FROM memory_privacy_deletion WHERE context_id=? AND source_event_id=? FOR UPDATE",
                claim.contextId(), claim.sourceEventId());
        require(deletion != null && claim.sourceHash().equals(deletion.get("source_hash")), MemoryErrorCode.VERSION_CONFLICT);
        int pendingTargets = store.jdbc.queryForObject("SELECT COUNT(*) FROM memory_privacy_cleanup_target WHERE context_id=? AND source_event_id=? AND cleanup_state<>'COMPLETE'",
                Integer.class, claim.contextId(), claim.sourceEventId());
        require(pendingTargets == 0, MemoryErrorCode.MEMORY_UNAVAILABLE);
        boolean delete = "DELETE".equals(deletion.get("action_kind"));
        String finalState = delete ? MemoryState.DELETED.name() : MemoryState.WITHDRAWN.name();
        store.jdbc.update("UPDATE memory_message SET state=?,payload_json=CASE WHEN ? THEN NULL ELSE payload_json END WHERE context_id=? AND source_event_id=? AND content_hash=?",
                finalState, delete, claim.contextId(), claim.sourceEventId(), claim.sourceHash());
        store.jdbc.update("UPDATE memory_summary SET state=?,payload_json=CASE WHEN ? THEN NULL ELSE payload_json END WHERE context_id=? AND state IN ('WITHDRAWN','DELETION_PENDING')",
                finalState, delete, claim.contextId());
        store.jdbc.update("UPDATE memory_entity_anchor SET state=?,payload_json=CASE WHEN ? THEN NULL ELSE payload_json END WHERE context_id=? AND state IN ('WITHDRAWN','DELETION_PENDING')",
                finalState, delete, claim.contextId());
        store.jdbc.update("UPDATE memory_matter SET state=?,payload_json=CASE WHEN ? THEN NULL ELSE payload_json END WHERE context_id=? AND state IN ('WITHDRAWN','DELETION_PENDING')",
                finalState, delete, claim.contextId());
        store.jdbc.update("UPDATE memory_preference_block SET state=? WHERE block_id IN (SELECT DISTINCT target_id FROM memory_source_dependency "
                        + "WHERE source_context_id=? AND source_event_id=? AND target_kind='PREFERENCE') AND state IN ('WITHDRAWN','DELETION_PENDING')",
                finalState, claim.contextId(), claim.sourceEventId());
        store.jdbc.update("UPDATE memory_shared_export SET state=?,payload_json=CASE WHEN ? THEN NULL ELSE payload_json END,"
                        + "lifecycle_revision=lifecycle_revision+1 WHERE owner_id=? AND source_domain_id=? AND source_agent_id=? "
                        + "AND source_conversation_id=? AND state IN ('WITHDRAWN','DELETION_PENDING')",
                finalState, delete, claim.contextKey().ownerId(), claim.contextKey().domainId(), claim.contextKey().agentId(), claim.contextKey().conversationId());
        LocalDateTime completedAt = now();
        store.jdbc.update("UPDATE memory_privacy_deletion SET cleanup_state='COMPLETE',last_error=NULL,updated_at=?,completed_at=? "
                        + "WHERE context_id=? AND source_event_id=?", completedAt, completedAt, claim.contextId(), claim.sourceEventId());
        int completed = store.jdbc.update("UPDATE memory_processing_job SET state='SUCCEEDED',last_error=NULL,lease_owner=NULL,lease_until=NULL "
                        + "WHERE job_id=? AND state='PROCESSING' AND lease_owner=? AND attempts=?",
                claim.jobId(), claim.leaseOwner(), claim.attempt());
        require(completed == 1, MemoryErrorCode.VERSION_CONFLICT);
    }

    private void verifyLease(Claim claim) {
        LocalDateTime now = now();
        var job = store.one("SELECT state,lease_owner,lease_until,attempts FROM memory_processing_job WHERE job_id=? FOR UPDATE", claim.jobId());
        require(job != null && "PROCESSING".equals(job.get("state")) && claim.leaseOwner().equals(job.get("lease_owner"))
                && number(job, "attempts") == claim.attempt() && utcInstant(job.get("lease_until")).isAfter(clock.instant()),
                MemoryErrorCode.VERSION_CONFLICT);
        var deletion = store.one("SELECT source_hash,cleanup_state FROM memory_privacy_deletion WHERE context_id=? AND source_event_id=? FOR UPDATE",
                claim.contextId(), claim.sourceEventId());
        require(deletion != null && claim.sourceHash().equals(deletion.get("source_hash"))
                && "PROCESSING".equals(deletion.get("cleanup_state")), MemoryErrorCode.VERSION_CONFLICT);
        var source = store.one("SELECT content_hash,state FROM memory_message WHERE context_id=? AND source_event_id=? FOR UPDATE",
                claim.contextId(), claim.sourceEventId());
        require(source != null && claim.sourceHash().equals(source.get("content_hash"))
                && Set.of("WITHDRAWN", "DELETION_PENDING", "DELETED").contains(source.get("state")), MemoryErrorCode.VERSION_CONFLICT);
    }

    private void fail(Claim claim, String error) {
        transaction(() -> {
            var job = store.one("SELECT state,lease_owner,attempts FROM memory_processing_job WHERE job_id=? FOR UPDATE", claim.jobId());
            if (job == null || !"PROCESSING".equals(job.get("state")) || !claim.leaseOwner().equals(job.get("lease_owner"))
                    || number(job, "attempts") != claim.attempt()) return null;
            boolean terminal = claim.attempt() >= maxAttempts;
            String state = terminal ? "FAILED" : "RETRY";
            LocalDateTime now = now();
            store.jdbc.update("UPDATE memory_processing_job SET state=?,last_error=?,available_at=?,lease_owner=NULL,lease_until=NULL WHERE job_id=?",
                    state, error, now.plusSeconds(retrySeconds), claim.jobId());
            store.jdbc.update("UPDATE memory_privacy_deletion SET cleanup_state=?,last_error=?,updated_at=? WHERE context_id=? AND source_event_id=?",
                    state, error, now, claim.contextId(), claim.sourceEventId());
            store.jdbc.update("UPDATE memory_privacy_cleanup_target SET last_error=?,updated_at=? WHERE context_id=? AND source_event_id=? AND cleanup_state<>'COMPLETE'",
                    error, now, claim.contextId(), claim.sourceEventId());
            return null;
        });
    }

    private String error(RuntimeException exception) {
        return exception instanceof MemoryException memory ? memory.code().name() : "CLEANUP_FAILED";
    }

    private static MemoryContextKey context(Map<String, Object> row) {
        return new MemoryContextKey((String) row.get("owner_id"), (String) row.get("domain_id"),
                (String) row.get("agent_id"), (String) row.get("conversation_id"));
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC); }
    private <T> T transaction(Supplier<T> action) {
        try { return tx.execute(status -> action.get()); }
        catch (DataAccessException | TransactionException exception) { throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
    }
}
