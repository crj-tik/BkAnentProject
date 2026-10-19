package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** SQL truth for v2 only. All methods run inside short archive transactions. */
@Repository
public class MemoryArchiveStore {
    static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MemoryArchiveStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    static String key(MemoryContextKey context) { return MemoryCanonicalHash.contentHash(context); }

    Map<String, Object> conversation(MemoryContextKey context) {
        requireTransaction();
        String id = key(context);
        // Concurrent first writers serialize on this one compound identity.
        jdbc.update("INSERT INTO memory_conversation(context_id,owner_id,domain_id,agent_id,conversation_id) "
                        + "VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE context_id=context_id",
                id, context.ownerId(), context.domainId(), context.agentId(), context.conversationId());
        Map<String, Object> row = one("SELECT * FROM memory_conversation WHERE context_id=? FOR UPDATE", id);
        require(context.ownerId().equals(row.get("owner_id")) && context.domainId().equals(row.get("domain_id"))
                && context.agentId().equals(row.get("agent_id")) && context.conversationId().equals(row.get("conversation_id")),
                MemoryErrorCode.FORBIDDEN);
        return row;
    }

    MemoryConversationVersion version(Map<String, Object> row) {
        return new MemoryConversationVersion(number(row, "revision"), number(row, "last_sequence"), (String) row.get("policy_version"));
    }

    Map<String, Object> one(String sql, Object... args) {
        requireTransaction();
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    List<MemoryMessage> messages(String contextId, long from, long through) {
        requireTransaction();
        return jdbc.query("SELECT payload_json FROM memory_message WHERE context_id=? AND sequence_no BETWEEN ? AND ? "
                        + "AND state='ACTIVE' ORDER BY sequence_no LIMIT 501",
                (rs, n) -> decode(rs.getString(1), MemoryMessage.class), contextId, from, through);
    }

    void enqueue(String contextId, String eventId, String kind) {
        String jobId = MemoryCanonicalHash.contentHash(List.of(contextId, eventId, kind));
        jdbc.update("INSERT INTO memory_processing_job(job_id,context_id,source_event_id,job_kind,available_at) "
                        + "VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE job_id=job_id", jobId, contextId, eventId, kind,
                LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    }

    void dependencies(String contextId, String kind, String targetId, long revision, List<MemorySourceDependency> dependencies) {
        for (var dependency : dependencies) {
            var source = dependency.source();
            jdbc.update("INSERT INTO memory_source_dependency(target_context_id,target_kind,target_id,target_revision,"
                            + "source_context_id,source_event_id,dependency_json) VALUES(?,?,?,?,?,?,?)",
                    contextId, kind, targetId, revision, key(source.contextKey()),
                    source.sourceEventId() == null ? source.sourceId() : source.sourceEventId(), encode(dependency));
        }
    }

    String encode(Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            require(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_PAYLOAD_BYTES,
                    MemoryErrorCode.INVALID_ARGUMENT);
            return json;
        } catch (MemoryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }

    <T> T decode(String json, Class<T> type) {
        try {
            require(json != null && json.length() <= MAX_PAYLOAD_BYTES, MemoryErrorCode.MEMORY_UNAVAILABLE);
            return mapper.readValue(json, type);
        } catch (Exception exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
    }

    static long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }

    /** DATETIME carries no zone. Persist and interpret the literal in UTC on every JDBC driver. */
    static Instant utcInstant(Object value) {
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
    }

    static void require(boolean valid, MemoryErrorCode code) { if (!valid) throw new MemoryException(code); }

    static void requireTransaction() {
        require(TransactionSynchronizationManager.isActualTransactionActive(), MemoryErrorCode.MEMORY_UNAVAILABLE);
    }
}
