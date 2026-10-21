package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** SQL truth only. No grant or export payload supplied by an RPC request is accepted here. */
@Repository
public class MemorySharingStore {
    public record ExportHeader(String exportId, MemoryContextKey sourceContext, MemoryRevision version,
                               MemoryGrantRef grant, String matterId, String purpose,
                               MemorySharedExport.State state, long lifecycleRevision) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MemorySharingStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<ExportHeader> findHeader(MemoryExportRef reference, boolean lock) {
        if (lock) requireTransaction();
        try {
            return jdbc.query("SELECT export_id, content_revision, content_hash, schema_version, owner_id, "
                    + "source_domain_id, source_agent_id, source_conversation_id, grant_id, grant_revision, "
                    + "matter_id, purpose, state, lifecycle_revision FROM memory_shared_export "
                    + "WHERE export_id=? AND content_revision=?" + (lock ? " FOR UPDATE" : ""),
                    (rs, row) -> header(rs), reference.exportId(), reference.version().revision()).stream().findFirst();
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    /** Caller holds this current grant row lock until the authorized SQL read finishes. */
    public Optional<MemoryShareGrant> findCurrentGrantForUpdate(String grantId) {
        requireTransaction();
        try {
            Optional<MemoryShareGrant> grant = jdbc.query("SELECT * FROM memory_share_grant WHERE grant_id=? FOR UPDATE",
                    (rs, row) -> grant(rs), grantId).stream().findFirst();
            if (grant.isPresent()) {
                var snapshots = jdbc.query("SELECT grant_payload FROM memory_share_grant_history WHERE grant_id=? AND grant_revision=?",
                        (rs, row) -> decode(rs.getString(1), MemoryShareGrant.class),
                        grantId, grant.get().grant().revision());
                if (snapshots.size() != 1 || !snapshots.get(0).equals(grant.get())) throw unavailable();
            }
            return grant;
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    /** Only called after the metadata authorization check and while grant/export locks are held. */
    public MemorySharedExport readPayload(MemoryExportRef reference) {
        requireTransaction();
        try {
            var rows = jdbc.query("SELECT e.payload_json,r.index_state FROM memory_shared_export e "
                            + "LEFT JOIN memory_shared_export_runtime r ON r.export_id=e.export_id AND r.content_revision=e.content_revision "
                            + "WHERE e.export_id=? AND e.content_revision=? AND e.state='COMMITTED'",
                    (rs, row) -> {
                        MemorySharedExport stored = decode(rs.getString("payload_json"), MemorySharedExport.class);
                        return new MemorySharedExport(stored.sourceExportId(), stored.sourceContext(), stored.association(),
                                stored.sourceInvocationId(), stored.version(), stored.grant(), stored.purpose(), stored.brief(),
                                stored.payload(), stored.dependencies(), stored.state(), stored.committedRef(),
                                rs.getString("index_state") == null ? MemoryIndexState.NOT_REQUESTED
                                        : MemoryIndexState.valueOf(rs.getString("index_state")));
                    },
                    reference.exportId(), reference.version().revision());
            if (rows.size() != 1) throw unavailable();
            return rows.get(0);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    /** Serializes first inserts that do not yet have an export/grant row to lock. */
    public void lockWriter() {
        requireTransaction();
        try {
            jdbc.queryForObject("SELECT lock_id FROM memory_shared_export_lock WHERE lock_id=1 FOR UPDATE", Integer.class);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private ExportHeader header(ResultSet rs) throws SQLException {
        try {
            return new ExportHeader(rs.getString("export_id"), context(rs),
                    new MemoryRevision(rs.getLong("content_revision"), rs.getString("content_hash"), rs.getString("schema_version")),
                    new MemoryGrantRef(rs.getString("grant_id"), rs.getLong("grant_revision")),
                    rs.getString("matter_id"), rs.getString("purpose"),
                    MemorySharedExport.State.valueOf(rs.getString("state")), rs.getLong("lifecycle_revision"));
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private MemoryShareGrant grant(ResultSet rs) throws SQLException {
        try {
            return verifiedGrant(rs);
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private MemoryShareGrant verifiedGrant(ResultSet rs) throws SQLException {
        MemoryShareGrant grant = decode(rs.getString("grant_payload"), MemoryShareGrant.class);
        boolean consistent = grant.grant().grantId().equals(rs.getString("grant_id"))
                && grant.grant().revision() == rs.getLong("grant_revision")
                && grant.sourceContext().equals(context(rs))
                && grant.scope().kind().name().equals(rs.getString("scope_kind"))
                && grant.scope().sharedScopeId().equals(rs.getString("shared_scope_id"))
                && Objects.equals(grant.matterId(), rs.getString("matter_id"))
                && grant.purpose().equals(rs.getString("purpose"))
                && grant.state().name().equals(rs.getString("state"))
                && grant.validFrom().truncatedTo(ChronoUnit.MICROS).equals(rs.getObject("valid_from", LocalDateTime.class).toInstant(ZoneOffset.UTC))
                && grant.validUntil().truncatedTo(ChronoUnit.MICROS).equals(rs.getObject("valid_until", LocalDateTime.class).toInstant(ZoneOffset.UTC));
        if (!consistent) throw unavailable();
        return grant;
    }

    private MemoryContextKey context(ResultSet rs) throws SQLException {
        return new MemoryContextKey(rs.getString("owner_id"), rs.getString("source_domain_id"),
                rs.getString("source_agent_id"), rs.getString("source_conversation_id"));
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            T decoded = json == null ? null : mapper.readValue(json, type);
            if (decoded == null) throw unavailable();
            return decoded;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private static MemoryException unavailable() { return new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    }
}
