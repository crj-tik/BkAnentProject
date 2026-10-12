package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;

@Service
public class ListingRagBackfillService {
    private final JdbcTemplate jdbc;
    private final ListingRagIdentity identity;
    private final ListingRagOutbox outbox;
    private final TransactionTemplate transactions;
    public ListingRagBackfillService(JdbcTemplate jdbc, ListingRagIdentity identity, ListingRagOutbox outbox,
                                     TransactionTemplate transactions) {
        this.jdbc = jdbc; this.identity = identity; this.outbox = outbox; this.transactions = transactions;
    }
    public List<RagIndexEvent> backfill(long afterId, int limit, RagCredentialContext credentials) {
        identity.requireAdmin(credentials);
        if (afterId < 0 || limit < 1 || limit > 200) { throw new RagException(RagErrorCode.INVALID_ARGUMENT, "backfill"); }
        // Persistent identity union includes physically deleted rows; LIMIT is a bound parameter.
        List<Long> ids = jdbc.query("SELECT listing_id FROM (SELECT id AS listing_id FROM listing_info UNION SELECT listing_id FROM listing_rag_revision) ids WHERE listing_id>? ORDER BY listing_id LIMIT ?",
                (rs, n) -> rs.getLong(1), afterId, limit);
        List<RagIndexEvent> events = new ArrayList<>();
        for (Long id : ids) {
            RagIndexEvent event = transactions.execute(status -> outbox.publishCurrent(id));
            events.add(event);
        }
        return List.copyOf(events);
    }
    public RagIndexEvent index(Long id, RagCredentialContext credentials) {
        identity.requireAdmin(credentials);
        return transactions.execute(status -> {
            outbox.lock(id);
            Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM listing_info WHERE id=? AND deleted=0", Long.class, id);
            if (exists == null || exists == 0) { throw new RagException(RagErrorCode.NOT_FOUND); }
            return outbox.publishCurrent(id);
        });
    }
    public RagIndexEvent reconcile(Long id, long expectedRevision, String hash, boolean deleteIntent) {
        return transactions.execute(status -> {
            outbox.lock(id);
            ListingRagOutbox.Revision revision = outbox.revision(id);
            if (revision == null || revision.revision() <= 0 || revision.revision() != expectedRevision
                    || hash == null || !hash.equals(revision.hash()) || revision.tombstone() != deleteIntent) {
                throw new RagException(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED);
            }
            Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM listing_info WHERE id=? AND deleted=0", Long.class, id);
            if ((exists != null && exists > 0) == deleteIntent) {
                throw new RagException(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED);
            }
            return outbox.publishCurrent(id);
        });
    }
}
