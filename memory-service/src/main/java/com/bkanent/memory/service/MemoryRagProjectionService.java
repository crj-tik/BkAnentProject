package com.bkanent.memory.service;

import com.bkanent.common.agent.SystemConstraintRecord;
import com.bkanent.common.agent.UserPreferenceRecord;
import com.bkanent.common.rag.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** SQL and complete projection are committed together; never calls an external service. */
@Service
public class MemoryRagProjectionService {
    public static final String PREFERENCE = "memory.preference";
    public static final String CONSTRAINT = "memory.constraint";
    public static final String SCHEMA = "memory-v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MemoryRagProjectionService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void lock() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Memory projection requires domain transaction");
        }
        jdbc.queryForObject("SELECT lock_id FROM memory_rag_lock WHERE lock_id = 1 FOR UPDATE", Integer.class);
    }

    public RagIndexEvent preference(UserPreferenceRecord record) {
        String id = RagDocumentIds.preference(record.userId(), record.preferenceKey());
        return publish(PREFERENCE, id, preferenceDocument(record));
    }

    public static RagDocument preferenceDocument(UserPreferenceRecord record) {
        String id = RagDocumentIds.preference(record.userId(), record.preferenceKey());
        if (record.confidence().compareTo(new BigDecimal("0.1")) < 0) return null;
        Map<String, RagValue> attrs = new LinkedHashMap<>();
        attrs.put("userId", RagValue.string(record.userId()));
        attrs.put("ownerId", RagValue.string(record.userId()));
        attrs.put("preferenceKey", RagValue.string(record.preferenceKey()));
        put(attrs, "category", record.category());
        attrs.put("confidence", RagValue.number(record.confidence()));
        attrs.put("observationCount", RagValue.number(record.observationCount()));
        if (record.lastObservedAt() != null) attrs.put("lastObservedAt", RagValue.instant(record.lastObservedAt().toInstant(ZoneOffset.UTC)));
        attrs.put("active", RagValue.bool(true));
        // Evidence/session identifiers are not needed by retrieval and do not enter the index.
        return new RagDocument("user_preference", id,
                "[" + (record.category() == null ? "general" : record.category()) + "] " + record.preferenceKey() + ": " + record.preferenceValue(), attrs);
    }

    public RagIndexEvent constraint(SystemConstraintRecord record) {
        String id = RagDocumentIds.constraint(record.constraintKey());
        return publish(CONSTRAINT, id, constraintDocument(record));
    }

    public static RagDocument constraintDocument(SystemConstraintRecord record) {
        if (!Boolean.TRUE.equals(record.active())) return null;
        Map<String, RagValue> attrs = new LinkedHashMap<>();
        attrs.put("constraintKey", RagValue.string(record.constraintKey()));
        put(attrs, "category", record.category());
        put(attrs, "tags", record.tags());
        attrs.put("active", RagValue.bool(true));
        return new RagDocument("system_constraint", record.constraintKey(),
                "[" + (record.category() == null ? "general" : record.category()) + "] " + record.constraintText(), attrs);
    }

    public RagIndexEvent publish(String space, String id, RagDocument document) {
        lock();
        var versions = jdbc.queryForList("SELECT revision FROM memory_rag_projection WHERE space_id=? AND document_id=?", Long.class, space, id);
        long revision = versions.isEmpty() ? 1 : Math.addExact(versions.get(0), 1);
        RagIndexOperation op = document == null ? RagIndexOperation.DELETE : RagIndexOperation.UPSERT;
        String eventId = UUID.randomUUID().toString();
        RagIndexEvent event = new RagIndexEvent(1, eventId, eventId, space, id, revision, SCHEMA, op,
                RagCanonicalHash.documentHash(op, document), document, Instant.now());
        String payload = encode(event);
        if (versions.isEmpty()) jdbc.update("INSERT INTO memory_rag_projection(space_id,document_id,revision,tombstone,event_payload) VALUES (?,?,?,?,?)", space, id, revision, document == null, payload);
        else jdbc.update("UPDATE memory_rag_projection SET revision=?,tombstone=?,event_payload=? WHERE space_id=? AND document_id=?", revision, document == null, payload, space, id);
        jdbc.update("INSERT INTO memory_rag_outbox(event_id,space_id,document_id,revision,event_payload,state,attempts,next_attempt_at) VALUES (?,?,?,?,?,'PENDING',0,?)", eventId, space, id, revision, payload, Timestamp.from(event.createdAt()));
        return event;
    }

    public RagIndexEvent decode(String value) {
        try { return json.readValue(value, RagIndexEvent.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid memory outbox projection"); }
    }

    private String encode(RagIndexEvent event) {
        try { return json.writeValueAsString(event); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot encode memory projection"); }
    }

    private static void put(Map<String, RagValue> attributes, String key, String value) {
        if (value != null) attributes.put(key, RagValue.string(value));
    }
}
