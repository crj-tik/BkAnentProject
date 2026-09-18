package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.agent.entity.AgentWorkflowCheckpointEntity;
import com.bkanent.agent.mapper.AgentWorkflowCheckpointMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Official Graph checkpoint saver backed by the existing workflow checkpoint table.
 *
 * <p>The graph runtime already provides the thread-safe in-memory lifecycle in
 * {@link MemorySaver}. This adapter keeps that behavior and mirrors every
 * checkpoint change to MySQL. On a new JVM the persisted checkpoints are loaded
 * back before the graph resumes.</p>
 */
public class DatabaseCheckpointSaver extends MemorySaver {

    private static final int ENVELOPE_VERSION = 1;

    private final AgentWorkflowCheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper;
    private final String graphName;

    public DatabaseCheckpointSaver(AgentWorkflowCheckpointMapper checkpointMapper,
                                   ObjectMapper objectMapper,
                                   String graphName) {
        this.checkpointMapper = checkpointMapper;
        // Checkpoint state contains approval timestamps and other Java time
        // values. Keep the saver independent from the caller's mapper setup;
        // graph instances can also be created directly in tests or tooling.
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.graphName = graphName;
    }

    @Override
    protected LinkedList<Checkpoint> loadedCheckpoints(RunnableConfig config,
                                                       LinkedList<Checkpoint> checkpoints) {
        if (!checkpoints.isEmpty()) {
            return checkpoints;
        }
        String threadId = threadId(config);
        List<AgentWorkflowCheckpointEntity> entities = checkpointMapper.selectList(
                new LambdaQueryWrapper<AgentWorkflowCheckpointEntity>()
                        .eq(AgentWorkflowCheckpointEntity::getTaskId, threadId)
                        .orderByAsc(AgentWorkflowCheckpointEntity::getCheckpointVersion)
        );
        entities.stream()
                .map(entity -> readCheckpoint(entity, entities))
                .filter(java.util.Objects::nonNull)
                .forEach(checkpoints::add);
        return checkpoints;
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config,
                                      LinkedList<Checkpoint> checkpoints,
                                      Checkpoint checkpoint) {
        persist(config, checkpoint);
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config,
                                     LinkedList<Checkpoint> checkpoints,
                                     Checkpoint checkpoint) {
        persist(config, checkpoint);
    }

    @Override
    protected void releasedCheckpoints(RunnableConfig config,
                                       LinkedList<Checkpoint> checkpoints,
                                       BaseCheckpointSaver.Tag tag) {
        // Keep terminal checkpoints available for audit and idempotent callback
        // lookup. The graph's in-memory release semantics still apply.
    }

    private void persist(RunnableConfig config, Checkpoint checkpoint) {
        Map<String, Object> state = checkpoint.getState();
        String taskId = text(state.get(OfficialSupervisorGraphKeys.TASK_ID), threadId(config));
        AgentWorkflowCheckpointEntity entity = new AgentWorkflowCheckpointEntity();
        entity.setTaskId(taskId);
        entity.setCheckpointVersion(nextVersion(taskId));
        entity.setSessionId(text(state.get(OfficialSupervisorGraphKeys.SESSION_ID), taskId));
        entity.setTraceId(text(state.get(OfficialSupervisorGraphKeys.TRACE_ID), taskId));
        entity.setWorkflowStatus(text(state.get(OfficialSupervisorGraphKeys.WORKFLOW_STATUS), "RUNNING"));
        entity.setSelectedAgentId(text(state.get(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID), null));
        entity.setPendingApprovalId(pendingApprovalId(state.get(OfficialSupervisorGraphKeys.PENDING_APPROVAL)));
        entity.setSnapshotJson(writeEnvelope(checkpoint, null));
        checkpointMapper.insert(entity);
    }

    private int nextVersion(String taskId) {
        AgentWorkflowCheckpointEntity latest = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentWorkflowCheckpointEntity>()
                        .eq(AgentWorkflowCheckpointEntity::getTaskId, taskId)
                        .orderByDesc(AgentWorkflowCheckpointEntity::getCheckpointVersion)
                        .last("limit 1")
        );
        return latest == null || latest.getCheckpointVersion() == null
                ? 1 : latest.getCheckpointVersion() + 1;
    }

    private Checkpoint readCheckpoint(AgentWorkflowCheckpointEntity entity,
                                      List<AgentWorkflowCheckpointEntity> allEntities) {
        OfficialCheckpointMigration.ReadResult result = OfficialCheckpointMigration.read(
                entity.getSnapshotJson(), graphName,
                String.valueOf(entity.getId()), objectMapper);
        if (result.checkpoint() == null) {
            return null;
        }
        if (result.migrated() && !alreadyMigrated(allEntities, result.migratedFromId())) {
            AgentWorkflowCheckpointEntity migrated = new AgentWorkflowCheckpointEntity();
            Map<String, Object> state = result.checkpoint().getState();
            migrated.setTaskId(text(state.get(OfficialSupervisorGraphKeys.TASK_ID), entity.getTaskId()));
            migrated.setCheckpointVersion(nextVersion(migrated.getTaskId()));
            migrated.setSessionId(text(state.get(OfficialSupervisorGraphKeys.SESSION_ID), entity.getSessionId()));
            migrated.setTraceId(text(state.get(OfficialSupervisorGraphKeys.TRACE_ID), entity.getTraceId()));
            migrated.setWorkflowStatus(text(state.get(OfficialSupervisorGraphKeys.WORKFLOW_STATUS), entity.getWorkflowStatus()));
            migrated.setSelectedAgentId(text(state.get(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID), entity.getSelectedAgentId()));
            migrated.setPendingApprovalId(pendingApprovalId(state.get(OfficialSupervisorGraphKeys.PENDING_APPROVAL)));
            migrated.setSnapshotJson(writeEnvelope(result.checkpoint(), result.migratedFromId()));
            checkpointMapper.insert(migrated);
        }
        return result.checkpoint();
    }

    private String writeEnvelope(Checkpoint checkpoint, String migratedFromId) {
        try {
            return objectMapper.writeValueAsString(new PersistedCheckpoint(
                    ENVELOPE_VERSION,
                    graphName,
                    checkpoint.getId(),
                    checkpoint.getNodeId(),
                    checkpoint.getNextNodeId(),
                    checkpoint.getState(),
                    migratedFromId
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize official graph checkpoint", exception);
        }
    }

    private boolean alreadyMigrated(List<AgentWorkflowCheckpointEntity> entities,
                                    String sourceId) {
        if (sourceId == null) {
            return true;
        }
        return entities.stream().anyMatch(entity -> {
            return sourceId.equals(OfficialCheckpointMigration.migratedFromId(
                    entity.getSnapshotJson(), objectMapper));
        });
    }

    private String pendingApprovalId(Object value) {
        if (value instanceof com.bkanent.common.agent.ApprovalRequest request) {
            return request.approvalId();
        }
        if (value instanceof Map<?, ?> map) {
            return text(map.get("approvalId"), null);
        }
        return null;
    }

    private String threadId(RunnableConfig config) {
        return config.threadId().orElse(BaseCheckpointSaver.THREAD_ID_DEFAULT);
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private record PersistedCheckpoint(int version,
                                       String graphName,
                                       String id,
                                       String nodeId,
                                       String nextNodeId,
                                       Map<String, Object> state,
                                       String migratedFromId) {
    }
}
