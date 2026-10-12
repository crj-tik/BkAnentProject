package com.bkanent.rag.index;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagIndexState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** All external values are JDBC parameters; table/column names are fixed control-schema names. */
public final class JdbcRagIndexRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcRagIndexRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(5);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(30);
    }

    <T> T committed(java.util.function.Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    LedgerDocument lock(String space, String id, boolean create, Instant now) {
        if (create) {
            jdbc.update("INSERT INTO rag_document_state (space_id,document_id,updated_at) VALUES (?,?,?) "
                    + "ON DUPLICATE KEY UPDATE document_id=document_id", space, id, timestamp(now));
        }
        return jdbc.query("SELECT * FROM rag_document_state WHERE space_id=? AND document_id=? FOR UPDATE",
                DOCUMENT_MAPPER, space, id).stream().findFirst().orElse(null);
    }

    Optional<LedgerDocument> document(String space, String id) {
        return jdbc.query("SELECT * FROM rag_document_state WHERE space_id=? AND document_id=?",
                DOCUMENT_MAPPER, space, id).stream().findFirst();
    }

    Optional<EventRow> event(String eventId) {
        return jdbc.query("SELECT * FROM rag_index_event WHERE event_id=?", EVENT_MAPPER, eventId)
                .stream().findFirst();
    }

    Optional<EventRow> revision(String space, String documentId, long revision) {
        return jdbc.query("SELECT * FROM rag_index_event WHERE space_id=? AND document_id=? AND revision=? "
                        + "AND state<>'CONFLICT' ORDER BY received_at,event_id LIMIT 1",
                EVENT_MAPPER, space, documentId, revision).stream().findFirst();
    }

    void insert(EventRow event, Instant created, Instant received) {
        jdbc.update("INSERT INTO rag_index_event (event_id,space_id,document_id,revision,content_hash,payload_hash,"
                        + "schema_version,operation,canonical_payload,state,error_code,created_at,received_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                event.eventId(), event.spaceId(), event.documentId(), event.revision(), event.hash(),
                event.payloadHash(), event.schemaVersion(), event.operation(), event.payload(),
                event.state().name(), name(event.error()), timestamp(created), timestamp(received));
    }

    void save(LedgerDocument d, Instant now) {
        jdbc.update("UPDATE rag_document_state SET latest_revision=?,latest_hash=?,latest_payload_hash=?,"
                        + "latest_event_id=?,schema_version=?,tombstone=?,state=?,keyword_applied_revision=?,"
                        + "vector_applied_revision=?,keyword_attempts=?,vector_attempts=?,keyword_error=?,vector_error=?,"
                        + "keyword_next_retry_at=?,vector_next_retry_at=?,reconcile_at=?,updated_at=? "
                        + "WHERE space_id=? AND document_id=?",
                d.revision, d.hash, d.payloadHash, d.eventId, d.schemaVersion, d.tombstone, d.state.name(),
                d.keywordRevision, d.vectorRevision, d.keywordAttempts, d.vectorAttempts,
                name(d.keywordError), name(d.vectorError), timestamp(d.keywordRetry), timestamp(d.vectorRetry),
                timestamp(d.reconcileAt), timestamp(now), d.spaceId, d.documentId);
    }

    void markRevision(LedgerDocument d) {
        jdbc.update("UPDATE rag_index_event SET state=?,error_code=? WHERE space_id=? AND document_id=? "
                        + "AND revision=? AND payload_hash=? AND state NOT IN ('CONFLICT','STALE')",
                d.state.name(), name(d.keywordError != null ? d.keywordError : d.vectorError),
                d.spaceId, d.documentId, d.revision, d.payloadHash);
    }

    void staleOlder(LedgerDocument d) {
        jdbc.update("UPDATE rag_index_event SET state='STALE',error_code=NULL WHERE space_id=? AND document_id=? "
                + "AND revision<? AND state<>'CONFLICT'", d.spaceId, d.documentId, d.revision);
    }

    List<DocumentKey> pending(Instant now, int limit) {
        return jdbc.query("SELECT space_id,document_id FROM rag_document_state WHERE latest_revision>0 AND "
                        + "(state='ACCEPTED' OR state='APPLYING' OR (state='RETRY_PENDING' AND "
                        + "((keyword_next_retry_at IS NOT NULL AND keyword_next_retry_at<=?) OR "
                        + "(vector_next_retry_at IS NOT NULL AND vector_next_retry_at<=?)))) "
                        + "ORDER BY updated_at,space_id,document_id LIMIT ?",
                (rs, row) -> new DocumentKey(rs.getString(1), rs.getString(2)),
                timestamp(now), timestamp(now), limit);
    }

    List<DocumentKey> reconciliation(Instant now, int limit) {
        return jdbc.query("SELECT space_id,document_id FROM rag_document_state WHERE state='APPLIED' "
                        + "AND (reconcile_at IS NULL OR reconcile_at<=?) ORDER BY updated_at,space_id,document_id LIMIT ?",
                (rs, row) -> new DocumentKey(rs.getString(1), rs.getString(2)), timestamp(now), limit);
    }

    private static final RowMapper<EventRow> EVENT_MAPPER = (rs, row) -> new EventRow(
            rs.getString("event_id"), rs.getString("space_id"), rs.getString("document_id"),
            rs.getLong("revision"), rs.getString("content_hash"), rs.getString("payload_hash"),
            rs.getString("schema_version"), rs.getString("operation"), rs.getString("canonical_payload"),
            RagIndexState.valueOf(rs.getString("state")), error(rs.getString("error_code")));

    private static final RowMapper<LedgerDocument> DOCUMENT_MAPPER = (rs, row) -> {
        LedgerDocument d = new LedgerDocument();
        d.spaceId = rs.getString("space_id");
        d.documentId = rs.getString("document_id");
        d.revision = rs.getLong("latest_revision");
        d.hash = rs.getString("latest_hash");
        d.payloadHash = rs.getString("latest_payload_hash");
        d.eventId = rs.getString("latest_event_id");
        d.schemaVersion = rs.getString("schema_version");
        d.tombstone = rs.getBoolean("tombstone");
        d.state = RagIndexState.valueOf(rs.getString("state"));
        d.keywordRevision = rs.getLong("keyword_applied_revision");
        d.vectorRevision = rs.getLong("vector_applied_revision");
        d.keywordAttempts = rs.getInt("keyword_attempts");
        d.vectorAttempts = rs.getInt("vector_attempts");
        d.keywordError = error(rs.getString("keyword_error"));
        d.vectorError = error(rs.getString("vector_error"));
        d.keywordRetry = instant(rs, "keyword_next_retry_at");
        d.vectorRetry = instant(rs, "vector_next_retry_at");
        d.reconcileAt = instant(rs, "reconcile_at");
        return d;
    };

    private static RagErrorCode error(String value) { return value == null ? null : RagErrorCode.valueOf(value); }
    private static String name(Enum<?> value) { return value == null ? null : value.name(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record DocumentKey(String spaceId, String documentId) { }
    record EventRow(String eventId, String spaceId, String documentId, long revision, String hash,
                    String payloadHash, String schemaVersion, String operation, String payload,
                    RagIndexState state, RagErrorCode error) {
        @Override public String toString() { return "EventRow[revision=" + revision + ", state=" + state + "]"; }
    }
}
