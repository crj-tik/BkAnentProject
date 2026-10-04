package com.bkanent.agent.orchestration;

import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.common.skill.runtime.SkillExecutionSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Durable lease and invocation facts. An uncertain execution is never automatically resent. */
@Component
public class OrchestrationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public OrchestrationStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public void register(String runId, SupervisorTaskRequest request) {
        try { jdbc.update("INSERT INTO agent_orchestration_run(run_id,user_id,request_json,lease_until_ms) VALUES (?,?,?,0)",
                runId, request.userId(), json(request)); }
        catch (DuplicateKeyException exception) { assertOwner(runId, request.userId()); }
    }
    public void assertOwner(String runId, String owner) {
        String actual = jdbc.queryForObject("SELECT user_id FROM agent_orchestration_run WHERE run_id=?", String.class, runId);
        if (owner == null || !owner.equals(actual)) throw new IllegalStateException("CONTINUATION_OWNER_MISMATCH");
    }
    public SupervisorTaskRequest originalRequest(String runId) {
        return read(jdbc.queryForObject("SELECT request_json FROM agent_orchestration_run WHERE run_id=?", String.class, runId), SupervisorTaskRequest.class);
    }
    public boolean lease(String runId, String token, long durationMs) {
        long now = System.currentTimeMillis();
        return jdbc.update("UPDATE agent_orchestration_run SET lease_owner=?,lease_until_ms=? WHERE run_id=? AND (lease_owner IS NULL OR lease_until_ms<?)",
                token, now + durationMs, runId, now) == 1;
    }
    public void renew(String runId, String token, long durationMs) {
        if (jdbc.update("UPDATE agent_orchestration_run SET lease_until_ms=? WHERE run_id=? AND lease_owner=?",
                System.currentTimeMillis() + durationMs, runId, token) != 1) throw new IllegalStateException("run lease lost");
    }
    public void release(String runId, String token) {
        jdbc.update("UPDATE agent_orchestration_run SET lease_owner=NULL,lease_until_ms=0 WHERE run_id=? AND lease_owner=?", runId, token);
    }
    public Invocation prepare(String runId, String callId, String capabilityId, String arguments) {
        String hash = hash(arguments);
        try { jdbc.update("INSERT INTO agent_tool_invocation(run_id,call_id,capability_id,arguments_hash,arguments_json,status,updated_at_ms) VALUES (?,?,?,?,?,'PENDING',?)",
                runId, callId, capabilityId, hash, canonical(arguments), System.currentTimeMillis()); }
        catch (DuplicateKeyException exception) { /* Existing call fact is checked below. */ }
        Invocation existing = find(runId, callId);
        if (!capabilityId.equals(existing.capabilityId()) || !hash.equals(existing.argumentsHash()))
            throw new IllegalStateException("TOOL_CALL_ID_CONFLICT");
        return existing;
    }
    public Invocation find(String runId, String callId) {
        var rows = jdbc.query("SELECT * FROM agent_tool_invocation WHERE run_id=? AND call_id=?", (result, row) ->
                new Invocation(runId, callId, result.getString("capability_id"), result.getString("arguments_hash"),
                        result.getString("status"), result.getString("result_json"), result.getString("remote_association_json")), runId, callId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public boolean claim(String runId, String callId) {
        return jdbc.update("UPDATE agent_tool_invocation SET status='EXECUTING',updated_at_ms=? WHERE run_id=? AND call_id=? AND status='PENDING'",
                System.currentTimeMillis(), runId, callId) == 1;
    }
    public void complete(String runId, String callId, String result) {
        jdbc.update("UPDATE agent_tool_invocation SET status='COMPLETED',result_json=?,updated_at_ms=? WHERE run_id=? AND call_id=?",
                result, System.currentTimeMillis(), runId, callId);
    }
    public void unknown(String runId, String callId) {
        jdbc.update("UPDATE agent_tool_invocation SET status='OUTCOME_UNKNOWN',updated_at_ms=? WHERE run_id=? AND call_id=? AND status<>'COMPLETED'",
                System.currentTimeMillis(), runId, callId);
    }
    public void remoteAccepted(String runId, String callId, Object association) {
        jdbc.update("UPDATE agent_tool_invocation SET remote_association_json=?,updated_at_ms=? WHERE run_id=? AND call_id=? AND status='EXECUTING'",
                json(association), System.currentTimeMillis(), runId, callId);
    }
    public void snapshot(String runId, SkillExecutionSnapshot snapshot) {
        String id = hash(json(Map.of("hash", snapshot.contentHash(), "scope", snapshot.capabilityIds().stream().sorted().toList(), "source", snapshot.source())));
        try { jdbc.update("INSERT INTO agent_skill_snapshot(run_id,snapshot_id,owner,skill_name,skill_version,content_hash,snapshot_json) VALUES (?,?,?,?,?,?,?)",
                runId, id, snapshot.definition().owner(), snapshot.definition().name(), snapshot.definition().version(), snapshot.contentHash(), json(snapshot)); }
        catch (DuplicateKeyException exception) { /* Same immutable snapshot already persisted. */ }
    }
    public String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(text).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalArgumentException("TOOL_ARGUMENTS_INVALID", exception); }
    }
    public String canonical(String json) {
        try { return mapper.writeValueAsString(sort(mapper.readTree(json))); }
        catch (Exception exception) { throw new IllegalArgumentException("TOOL_ARGUMENTS_INVALID", exception); }
    }
    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>(); node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> sorted.set(key, sort(value))); return sorted;
        }
        if (node.isArray()) { ArrayNode array = mapper.createArrayNode(); node.forEach(item -> array.add(sort(item))); return array; }
        return node;
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public record Invocation(String runId, String callId, String capabilityId, String argumentsHash,
                             String status, String result, String remoteAssociation) {}
}
