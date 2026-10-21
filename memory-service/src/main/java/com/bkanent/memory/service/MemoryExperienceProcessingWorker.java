package com.bkanent.memory.service;

import com.bkanent.common.memory.MemoryErrorCode;
import com.bkanent.common.memory.MemoryException;
import com.bkanent.memory.config.MemoryExperienceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.bkanent.memory.service.MemoryArchiveStore.*;

/** Lease-fenced worker; failures never block the domain request that submitted the event. */
@Service
@ConditionalOnProperty(prefix = "memory.v2.experience", name = {"enabled", "processing-enabled"}, havingValue = "true")
@ConditionalOnProperty(prefix = "memory.v2", name = "archive-enabled", havingValue = "true")
public class MemoryExperienceProcessingWorker {
    private final MemoryArchiveStore store;
    private final MemoryExperienceService experiences;
    private final MemoryExperienceProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean();

    public MemoryExperienceProcessingWorker(MemoryArchiveStore store, MemoryExperienceService experiences,
            MemoryExperienceProperties properties, PlatformTransactionManager manager) {
        this(store, experiences, properties, manager, Clock.systemUTC());
    }

    MemoryExperienceProcessingWorker(MemoryArchiveStore store, MemoryExperienceService experiences,
            MemoryExperienceProperties properties, PlatformTransactionManager manager, Clock clock) {
        this.store = store;
        this.experiences = experiences;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(10);
    }

    @Scheduled(fixedDelayString = "${memory.v2.experience.poll-ms:5000}", scheduler = "memoryExperienceScheduler")
    public void runOnce() {
        if (!properties.isEnabled() || !properties.isProcessingEnabled() || !running.compareAndSet(false, true)) return;
        try {
            for (int i = 0; i < properties.getBatchSize(); i++) {
                Claim claim = transaction(this::claim);
                if (claim == null) break;
                if (!claim.exhausted()) experiences.process(claim.contextId(), claim.evidenceId(), claim.leaseOwner(), claim.attempt());
            }
        } finally {
            running.set(false);
        }
    }

    Claim claim() {
        var now = now();
        var row = store.one("SELECT j.* FROM memory_processing_job j JOIN memory_experience_event e "
                + "ON e.context_id=j.context_id AND e.evidence_id=j.source_event_id "
                + "WHERE j.job_kind='ASSESS_EXPERIENCE' AND e.source_state='ACTIVE' AND "
                + "((j.state IN ('PENDING','RETRY') AND j.available_at<=?) OR (j.state='PROCESSING' AND j.lease_until<=?)) "
                + "ORDER BY j.available_at,j.job_id LIMIT 1 FOR UPDATE SKIP LOCKED", now, now);
        if (row == null) return null;
        String jobId = (String) row.get("job_id");
        int attempts = (int) number(row, "attempts");
        String contextId = (String) row.get("context_id");
        String evidenceId = (String) row.get("source_event_id");
        if (attempts >= properties.getMaxAttempts()) {
            store.jdbc.update("UPDATE memory_processing_job SET state='FAILED',last_error='RETRY_EXHAUSTED',lease_owner=NULL,lease_until=NULL WHERE job_id=?", jobId);
            store.jdbc.update("UPDATE memory_experience_event SET updated_at=? WHERE context_id=? AND evidence_id=?", now, contextId, evidenceId);
            return new Claim(contextId, evidenceId, "exhausted", attempts, true);
        }
        String lease = UUID.randomUUID().toString();
        store.jdbc.update("UPDATE memory_processing_job SET state='PROCESSING',attempts=attempts+1,lease_owner=?,lease_until=?,last_error=NULL WHERE job_id=?",
                lease, now.plusSeconds(properties.getLeaseSeconds()), jobId);
        store.jdbc.update("UPDATE memory_experience_event SET updated_at=? WHERE context_id=? AND evidence_id=?", now, contextId, evidenceId);
        return new Claim(contextId, evidenceId, lease, attempts + 1, false);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC); }
    private static long number(Map<String, Object> row, String field) { return MemoryArchiveStore.number(row, field); }
    private <T> T transaction(Supplier<T> action) { return tx.execute(status -> action.get()); }
    record Claim(String contextId, String evidenceId, String leaseOwner, int attempt, boolean exhausted) { }
}
