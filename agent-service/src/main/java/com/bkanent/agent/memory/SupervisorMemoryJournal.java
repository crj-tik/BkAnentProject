package com.bkanent.agent.memory;

import com.bkanent.agent.orchestration.*;
import com.bkanent.common.memory.*;
import com.bkanent.memory.runtime.MemoryWorkingSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Restartable reference journal rebuilt from the existing immutable graph checkpoints. No business-body copy. */
@Repository
public class SupervisorMemoryJournal {
    public record Fact(String eventId, long sequence, String hash, String permit, String state, MemoryMessage message) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final SupervisorMemoryProjection projection;
    public SupervisorMemoryJournal(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.mapper = mapper; tx = new TransactionTemplate(transactions); tx.setTimeout(5);
        projection = new SupervisorMemoryProjection(mapper);
    }

    public void enroll(MemoryWorkingSession session) {
        tx.executeWithoutResult(status -> {
            String key = MemoryCanonicalHash.contentHash(session.snapshot().contextKey());
            jdbc.update("INSERT INTO agent_memory_cursor(context_id,writer_fence,last_sequence) VALUES(?,?,?) ON DUPLICATE KEY UPDATE context_id=context_id",
                    key, session.authority().fence(), session.snapshot().version().lastSequence());
            var row = jdbc.queryForMap("SELECT * FROM agent_memory_cursor WHERE context_id=? FOR UPDATE", key);
            require(((Number) row.get("writer_fence")).longValue() <= session.authority().fence());
            jdbc.update("UPDATE agent_memory_cursor SET writer_fence=?,last_sequence=? WHERE context_id=?", session.authority().fence(),
                    Math.max(((Number) row.get("last_sequence")).longValue(), session.snapshot().version().lastSequence()), key);
        });
    }

    public void capture(ToolLoopState live, MemoryWorkingSession session) {
        session.renew();
        String key = MemoryCanonicalHash.contentHash(session.snapshot().contextKey());
        var checkpoints = jdbc.queryForList("SELECT checkpoint_version,snapshot_json FROM agent_workflow_checkpoint WHERE task_id=? ORDER BY checkpoint_version DESC LIMIT 1", live.runId);
        if (checkpoints.isEmpty()) throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
        var checkpoint = checkpoints.get(0);
        ToolLoopState state = checkpointState((String) checkpoint.get("snapshot_json"), live.runId, session.snapshot().contextKey());
        int version = ((Number) checkpoint.get("checkpoint_version")).intValue();
        tx.executeWithoutResult(status -> {
            var cursor = jdbc.queryForMap("SELECT * FROM agent_memory_cursor WHERE context_id=? FOR UPDATE", key);
            require(((Number) cursor.get("writer_fence")).longValue() == session.authority().fence());
            long sequence = ((Number) cursor.get("last_sequence")).longValue();
            for (int index = 0; index < state.messages.size(); index++) {
                var stored = state.messages.get(index);
                String event = event(live.runId, index, stored);
                if (jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_delivery WHERE context_id=? AND event_id=?", Integer.class, key, event) != 0) continue;
                MemoryMessage message = projection.project(session.snapshot().contextKey(), live.runId, event, sequence + 1, stored);
                jdbc.update("INSERT INTO agent_memory_delivery(context_id,event_id,run_id,checkpoint_version,message_ordinal,sequence_no,content_hash) VALUES(?,?,?,?,?,?,?)",
                        key, event, live.runId, version, index, ++sequence, message.source().version().contentHash());
            }
            jdbc.update("UPDATE agent_memory_cursor SET last_sequence=? WHERE context_id=?", sequence, key);
        });
    }

    public List<Fact> pending(MemoryContextKey context) {
        String key = MemoryCanonicalHash.contentHash(context);
        var rows = jdbc.queryForList("SELECT * FROM agent_memory_delivery WHERE context_id=? AND state='PENDING' ORDER BY sequence_no LIMIT 50", key);
        List<Fact> facts = new ArrayList<>();
        for (var row : rows) {
            String run = (String) row.get("run_id");
            var snapshots = jdbc.queryForList("SELECT snapshot_json FROM agent_workflow_checkpoint WHERE task_id=? AND checkpoint_version=?", run, row.get("checkpoint_version"));
            if (snapshots.size() != 1) throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
            var state = checkpointState((String) snapshots.get(0).get("snapshot_json"), run, context);
            int ordinal = ((Number) row.get("message_ordinal")).intValue();
            if (ordinal < 0 || ordinal >= state.messages.size()) throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
            var stored = state.messages.get(ordinal);
            String event = (String) row.get("event_id");
            require(event.equals(event(run, ordinal, stored)));
            var message = projection.project(context, run, event, ((Number) row.get("sequence_no")).longValue(), stored);
            require(message.source().version().contentHash().equals(row.get("content_hash")));
            facts.add(new Fact(event, message.sequence(), message.source().version().contentHash(), (String) row.get("permit_id"), "PENDING", message));
        }
        return facts;
    }

    public void permit(MemoryContextKey context, Fact fact, String permit) {
        jdbc.update("UPDATE agent_memory_delivery SET permit_id=? WHERE context_id=? AND event_id=? AND content_hash=? AND state='PENDING'",
                permit, MemoryCanonicalHash.contentHash(context), fact.eventId(), fact.hash());
    }
    public void received(MemoryContextKey context, Fact fact) {
        jdbc.update("UPDATE agent_memory_delivery SET state='RECEIVED',last_error=NULL WHERE context_id=? AND event_id=? AND content_hash=?",
                MemoryCanonicalHash.contentHash(context), fact.eventId(), fact.hash());
    }
    public void failed(MemoryContextKey context, Fact fact, MemoryErrorCode error) {
        jdbc.update("UPDATE agent_memory_delivery SET last_error=? WHERE context_id=? AND event_id=?", error.name(), MemoryCanonicalHash.contentHash(context), fact.eventId());
    }

    private ToolLoopState checkpointState(String json, String run, MemoryContextKey context) {
        try {
            var envelope = mapper.readTree(json);
            if (!SupervisorToolLoopGraph.RUNNER_VERSION.equals(envelope.path("graphName").asText())) throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
            var state = mapper.treeToValue(envelope.path("state").path(SupervisorToolLoopGraph.STATE_KEY), ToolLoopState.class);
            require(SupervisorToolLoopGraph.RUNNER_VERSION.equals(state.runnerVersion)
                    && run.equals(state.runId) && context.ownerId().equals(state.userId) && context.conversationId().equals(state.sessionId));
            return state;
        } catch (MemoryException exception) { throw exception; }
        catch (Exception exception) { throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED); }
    }
    private String event(String run, int ordinal, StoredModelMessage stored) {
        try { return MemoryCanonicalHash.contentHash(List.of(run, ordinal, mapper.writeValueAsString(stored))); }
        catch (Exception exception) { throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION); }
    }
    public Set<String> currentEvents(ToolLoopState state) {
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < state.messages.size(); i++) ids.add(event(state.runId, i, state.messages.get(i)));
        return ids;
    }
    private static void require(boolean valid) { if (!valid) throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT); }
}
