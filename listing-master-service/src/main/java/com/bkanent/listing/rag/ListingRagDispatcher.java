package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagIndexRpcService;
import com.bkanent.listing.config.ListingRagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persisted leases and event identity survive crashes; no remote calls in business transactions. */
@Service
public class ListingRagDispatcher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectProvider<RagIndexRpcService> rpc;
    private final ListingRagIdentity identity;
    private final ListingRagProperties properties;
    public ListingRagDispatcher(JdbcTemplate jdbc, ObjectMapper json,
                                @Qualifier("ragIndexRpcService") ObjectProvider<RagIndexRpcService> rpc,
                                ListingRagIdentity identity, ListingRagProperties properties) {
        this.jdbc = jdbc; this.json = json; this.rpc = rpc; this.identity = identity; this.properties = properties;
    }
    @Scheduled(fixedDelayString = "${listing.rag.dispatch-delay-ms:1000}")
    public void dispatch() {
        if (!properties.isDispatcherEnabled()) { return; }
        List<String> ids = jdbc.query("SELECT event_id FROM listing_rag_outbox WHERE state IN ('PENDING','RETRY_PENDING','ACCEPTED','APPLYING') AND attempts<5 AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<?) ORDER BY created_at LIMIT 20",
                (rs, n) -> rs.getString(1), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        for (String id : ids) {
            try { deliver(id); } catch (RagException ignored) { /* persisted safe error, bounded retry */ }
        }
    }
    public RagIndexStatus deliver(String eventId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Remote RAG delivery cannot run inside a transaction");
        }
        String lease = UUID.randomUUID().toString();
        int claimed = jdbc.update("UPDATE listing_rag_outbox SET lease_token=?,lease_until=? WHERE event_id=? AND (lease_until IS NULL OR lease_until<?)",
                lease, Timestamp.from(Instant.now().plusSeconds(60)), eventId, Timestamp.from(Instant.now()));
        if (claimed != 1) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
        try {
            RagIndexRpcService provider = rpc.getIfAvailable();
            if (provider == null) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
            String payload = jdbc.queryForObject("SELECT payload FROM listing_rag_outbox WHERE event_id=? AND lease_token=?", String.class, eventId, lease);
            RagIndexEvent event = json.readValue(payload, RagIndexEvent.class);
            RagStatusRequest statusRequest = new RagStatusRequest(1, UUID.randomUUID().toString(), event.spaceId(), event.eventId(), null);
            RagIndexStatus status;
            try {
                status = provider.getStatus(statusRequest, identity.sign("rag-service", RagOperation.GET_STATUS,
                        statusRequest.requestId(), RagCanonicalHash.requestHash(statusRequest), RagActorMode.SERVICE, ListingRagIdentity.SERVICE, null));
            } catch (RagException e) {
                if (e.code() != RagErrorCode.NOT_FOUND) { throw e; }
                status = provider.submit(event, identity.sign("rag-service", RagOperation.SUBMIT,
                        event.requestId(), RagCanonicalHash.requestHash(event), RagActorMode.SERVICE, ListingRagIdentity.SERVICE, null));
            }
            if (status == null || !event.eventId().equals(status.eventId()) || !event.spaceId().equals(status.spaceId())
                    || !event.documentId().equals(status.documentId()) || event.revision() != status.revision()
                    || !event.contentHash().equals(status.contentHash())) {
                throw new RagException(RagErrorCode.INDEX_CONFLICT);
            }
            jdbc.update("UPDATE listing_rag_outbox SET state=?,error_code=?,next_attempt_at=?,lease_until=NULL,lease_token=NULL WHERE event_id=? AND lease_token=?",
                    status.state().name(), status.errorCode() == null ? null : status.errorCode().name(), Timestamp.from(Instant.now().plusSeconds(2)), eventId, lease);
            return status;
        } catch (Exception e) {
            RagErrorCode code = e instanceof RagException re ? re.code() : RagErrorCode.DEPENDENCY_UNAVAILABLE;
            Integer attempts = jdbc.queryForObject("SELECT attempts FROM listing_rag_outbox WHERE event_id=? AND lease_token=?", Integer.class, eventId, lease);
            int delay = 1 << Math.min(4, attempts == null ? 0 : attempts);
            jdbc.update("UPDATE listing_rag_outbox SET state='RETRY_PENDING',attempts=attempts+1,error_code=?,next_attempt_at=?,lease_until=NULL,lease_token=NULL WHERE event_id=? AND lease_token=?",
                    code.name(), Timestamp.from(Instant.now().plusMillis(delay * 1000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(250))), eventId, lease);
            throw new RagException(code);
        }
    }
}
