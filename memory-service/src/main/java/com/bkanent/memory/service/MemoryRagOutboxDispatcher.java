package com.bkanent.memory.service;

import com.bkanent.common.rag.*;
import com.bkanent.common.memory.MemoryIndexState;
import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.memory.config.MemoryRagProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reads committed rows only. Unknown submit/status outcomes retry the original event identity. */
@Component
public class MemoryRagOutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final MemoryRagProjectionService projection;
    private final MemoryRagSecurity security;
    private final RagIndexRpcService index;
    private final MemoryRagProperties properties;
    private final TransactionTemplate transactions;
    private record Claim(String eventId, String payload, String state, int attempts, String leaseOwner) { }
    public MemoryRagOutboxDispatcher(JdbcTemplate jdbc, MemoryRagProjectionService projection, MemoryRagSecurity security, RagIndexRpcService index, MemoryRagProperties properties) {
        this.jdbc = jdbc; this.projection = projection; this.security = security; this.index = index; this.properties = properties;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(5);
    }

    @Scheduled(fixedDelayString = "${memory.rag.dispatch-interval-ms:1000}")
    public void dispatch() {
        if (!properties.isDispatcherEnabled()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Outbox dispatch inside source transaction");
        for (int i = 0; i < properties.getDispatchBatchSize(); i++) {
            Claim claim = claimNext();
            if (claim == null) return;
            process(claim);
        }
    }

    private Claim claimNext() {
        Instant now = Instant.now();
        Timestamp nowTimestamp = Timestamp.from(now);
        return transactions.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT event_id,event_payload,state,attempts FROM memory_rag_outbox "
                    + "WHERE state IN ('PENDING','WAITING') AND next_attempt_at<=? AND attempts<? AND (lease_until IS NULL OR lease_until<=?) "
                    + "ORDER BY created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED",
                    nowTimestamp, properties.getMaxAttempts(), nowTimestamp);
            if (rows.isEmpty()) return null;
            var row = rows.get(0);
            String id = row.get("event_id").toString();
            String leaseOwner = UUID.randomUUID().toString();
            Timestamp leaseUntil = Timestamp.from(now.plusMillis(properties.getDispatchLeaseMs()));
            int updated = jdbc.update("UPDATE memory_rag_outbox SET lease_owner=?,lease_until=? WHERE event_id=? AND state=? "
                            + "AND (lease_until IS NULL OR lease_until<=?)",
                    leaseOwner, leaseUntil, id, row.get("state"), nowTimestamp);
            if (updated != 1) return null;
            return new Claim(id, row.get("event_payload").toString(), row.get("state").toString(),
                    ((Number) row.get("attempts")).intValue(), leaseOwner);
        });
    }

    private void process(Claim claim) {
        RagErrorCode error = null;
        String state = "PENDING";
        RagIndexEvent completedEvent = null;
        try {
            RagIndexEvent event = projection.decode(claim.payload());
            completedEvent = event;
            RagIndexStatus status;
            if ("WAITING".equals(claim.state())) {
                RagStatusRequest request = new RagStatusRequest(1, UUID.randomUUID().toString(), event.spaceId(), event.eventId(), null);
                try { status = index.getStatus(request, security.serviceAuthentication(RagOperation.GET_STATUS, request.requestId(), request)); }
                catch (RagException ex) { if (ex.code() != RagErrorCode.NOT_FOUND) throw ex; status = submit(event); }
            } else status = submit(event);
            verifyStatus(event, status);
            state = switch (status.state()) {
                case APPLIED -> "APPLIED";
                case STALE -> "STALE";
                case CONFLICT -> "CONFLICT";
                default -> "WAITING";
            };
            error = status.errorCode();
        } catch (RagException ex) { error = ex.code(); }
        catch (RuntimeException ex) { error = RagErrorCode.DEPENDENCY_UNAVAILABLE; }
        boolean failed = error != null || "PENDING".equals(state);
        int nextAttempts = failed ? claim.attempts() + 1 : 0;
        if (failed && nextAttempts >= properties.getMaxAttempts()) state = "EXHAUSTED";
        String completedState = state;
        int completedAttempts = nextAttempts;
        String errorCode = error == null ? null : error.name();
        // Late results from an expired/taken-over lease cannot overwrite newer progress.
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp nextAttempt = Timestamp.from(Instant.now().plusMillis(properties.getRetryDelayMs() * (1L << Math.min(nextAttempts, 4))));
        RagIndexEvent resultEvent = completedEvent;
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE memory_rag_outbox SET state=?,attempts=?,next_attempt_at=?,error_code=?,lease_owner=NULL,lease_until=NULL "
                            + "WHERE event_id=? AND lease_owner=? AND lease_until>? AND state IN ('PENDING','WAITING')",
                    completedState, completedAttempts, nextAttempt, errorCode, claim.eventId(), claim.leaseOwner(), now);
            if (updated == 1 && resultEvent != null) updateSharedExportIndexState(claim.eventId(), resultEvent, completedState, errorCode);
        });
    }

    private void updateSharedExportIndexState(String eventId, RagIndexEvent event, String outboxState, String errorCode) {
        List<Map<String, Object>> mappings = jdbc.queryForList(
                "SELECT export_id,content_revision FROM memory_shared_export_projection WHERE event_id=? FOR UPDATE", eventId);
        if (mappings.isEmpty()) return;
        MemoryIndexState itemState = switch (outboxState) {
            case "APPLIED" -> event.operation() == RagIndexOperation.DELETE
                    ? MemoryIndexState.INDEX_REMOVED : MemoryIndexState.INDEX_READY;
            case "STALE", "CONFLICT", "EXHAUSTED" -> MemoryIndexState.INDEX_FAILED;
            default -> MemoryIndexState.INDEX_PENDING;
        };
        jdbc.update("UPDATE memory_shared_export_projection SET index_state=? WHERE event_id=?", itemState.name(), eventId);
        for (Map<String, Object> mapping : mappings) {
            String exportId = mapping.get("export_id").toString();
            long revision = ((Number) mapping.get("content_revision")).longValue();
            List<String> states = jdbc.queryForList("SELECT index_state FROM memory_shared_export_projection "
                    + "WHERE export_id=? AND content_revision=?", String.class, exportId, revision);
            if (states.isEmpty()) continue;
            String exportState = jdbc.queryForObject("SELECT state FROM memory_shared_export WHERE export_id=? AND content_revision=?",
                    String.class, exportId, revision);
            MemoryIndexState aggregate;
            if ("WITHDRAWN".equals(exportState) || "DELETION_PENDING".equals(exportState) || "DELETED".equals(exportState)) {
                aggregate = states.stream().allMatch(MemoryIndexState.INDEX_REMOVED.name()::equals)
                        ? MemoryIndexState.INDEX_REMOVED
                        : states.stream().anyMatch(MemoryIndexState.INDEX_FAILED.name()::equals)
                                ? MemoryIndexState.INDEX_FAILED : MemoryIndexState.INDEX_PENDING;
            } else {
                boolean projectionsSettled = states.stream().allMatch(value ->
                        MemoryIndexState.INDEX_READY.name().equals(value) || MemoryIndexState.INDEX_REMOVED.name().equals(value));
                boolean anyReady = states.stream().anyMatch(MemoryIndexState.INDEX_READY.name()::equals);
                aggregate = projectionsSettled && anyReady
                        ? MemoryIndexState.INDEX_READY
                        : states.stream().anyMatch(MemoryIndexState.INDEX_FAILED.name()::equals)
                                ? MemoryIndexState.INDEX_FAILED : MemoryIndexState.INDEX_PENDING;
            }
            jdbc.update("UPDATE memory_shared_export_runtime SET index_state=?,last_error=? WHERE export_id=? AND content_revision=?",
                    aggregate.name(), aggregate == MemoryIndexState.INDEX_FAILED ? errorCode : null, exportId, revision);
        }
    }

    private RagIndexStatus submit(RagIndexEvent event) {
        return index.submit(event, security.serviceAuthentication(RagOperation.SUBMIT, event.requestId(), event));
    }

    private void verifyStatus(RagIndexEvent event, RagIndexStatus status) {
        if (status == null || !event.eventId().equals(status.eventId()) || !event.spaceId().equals(status.spaceId()) || !event.documentId().equals(status.documentId()) || event.revision() != status.revision() || !event.contentHash().equals(status.contentHash())) throw new RagException(RagErrorCode.INDEX_CONFLICT);
    }
}
