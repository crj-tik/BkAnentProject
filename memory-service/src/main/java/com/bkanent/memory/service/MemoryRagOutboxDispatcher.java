package com.bkanent.memory.service;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.memory.config.MemoryRagProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Reads committed rows only. Unknown submit/status outcomes retry the original event identity. */
@Component
public class MemoryRagOutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final MemoryRagProjectionService projection;
    private final MemoryRagSecurity security;
    private final RagIndexRpcService index;
    private final MemoryRagProperties properties;
    public MemoryRagOutboxDispatcher(JdbcTemplate jdbc, MemoryRagProjectionService projection, MemoryRagSecurity security, RagIndexRpcService index, MemoryRagProperties properties) {
        this.jdbc = jdbc; this.projection = projection; this.security = security; this.index = index; this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${memory.rag.dispatch-interval-ms:1000}")
    public void dispatch() {
        if (!properties.isDispatcherEnabled()) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Outbox dispatch inside source transaction");
        var rows = jdbc.queryForList("SELECT event_id,event_payload,state,attempts FROM memory_rag_outbox WHERE state IN ('PENDING','WAITING') AND next_attempt_at<=? AND attempts<? ORDER BY created_at,event_id LIMIT ?", Timestamp.from(Instant.now()), properties.getMaxAttempts(), properties.getDispatchBatchSize());
        for (var row : rows) {
            String id = row.get("event_id").toString();
            int attempts = ((Number) row.get("attempts")).intValue();
            RagErrorCode error = null;
            String state = "PENDING";
            try {
                RagIndexEvent event = projection.decode(jdbc.queryForObject("SELECT event_payload FROM memory_rag_outbox WHERE event_id=?", String.class, id));
                RagIndexStatus status;
                if ("WAITING".equals(row.get("state"))) {
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
            int nextAttempts = failed ? attempts + 1 : 0;
            if (failed && nextAttempts >= properties.getMaxAttempts()) state = "EXHAUSTED";
            // No exception text or signed credentials can enter persisted payload/error.
            jdbc.update("UPDATE memory_rag_outbox SET state=?,attempts=?,next_attempt_at=?,error_code=? WHERE event_id=? AND state IN ('PENDING','WAITING')", state, nextAttempts, Timestamp.from(Instant.now().plusMillis(properties.getRetryDelayMs() * (1L << Math.min(nextAttempts, 4)))), error == null ? null : error.name(), id);
        }
    }

    private RagIndexStatus submit(RagIndexEvent event) {
        return index.submit(event, security.serviceAuthentication(RagOperation.SUBMIT, event.requestId(), event));
    }

    private void verifyStatus(RagIndexEvent event, RagIndexStatus status) {
        if (status == null || !event.eventId().equals(status.eventId()) || !event.spaceId().equals(status.spaceId()) || !event.documentId().equals(status.documentId()) || event.revision() != status.revision() || !event.contentHash().equals(status.contentHash())) throw new RagException(RagErrorCode.INDEX_CONFLICT);
    }
}
