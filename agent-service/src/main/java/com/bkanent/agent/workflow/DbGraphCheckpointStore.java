package com.bkanent.agent.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.agent.entity.AgentWorkflowCheckpointEntity;
import com.bkanent.agent.mapper.AgentWorkflowCheckpointMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * DbGraphCheckpointStore 数据库版检查点存储，支持多版本 checkpoint（多审批场景）。
 */
@Primary
@Component
public class DbGraphCheckpointStore implements GraphCheckpointStore {

    private final AgentWorkflowCheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper;

    public DbGraphCheckpointStore(AgentWorkflowCheckpointMapper checkpointMapper,
                                  ObjectMapper objectMapper) {
        this.checkpointMapper = checkpointMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(SupervisorWorkflowState state) {
        AgentWorkflowCheckpointEntity entity = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentWorkflowCheckpointEntity>()
                        .eq(AgentWorkflowCheckpointEntity::getTaskId, state.taskId())
                        .orderByDesc(AgentWorkflowCheckpointEntity::getCheckpointVersion)
                        .last("limit 1")
        );
        int nextVersion = (entity == null) ? 1 : entity.getCheckpointVersion() + 1;

        AgentWorkflowCheckpointEntity newEntity = new AgentWorkflowCheckpointEntity();
        newEntity.setTaskId(state.taskId());
        newEntity.setCheckpointVersion(nextVersion);
        newEntity.setSessionId(state.sessionId());
        newEntity.setTraceId(state.traceId());
        newEntity.setWorkflowStatus(state.workflowStatus().name());
        newEntity.setSelectedAgentId(state.selectedAgentId());
        newEntity.setPendingApprovalId(state.pendingApproval() == null ? null : state.pendingApproval().approvalId());
        newEntity.setSnapshotJson(writeJson(state));
        checkpointMapper.insert(newEntity);
    }

    @Override
    public Optional<SupervisorWorkflowState> load(String taskId) {
        AgentWorkflowCheckpointEntity entity = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentWorkflowCheckpointEntity>()
                        .eq(AgentWorkflowCheckpointEntity::getTaskId, taskId)
                        .orderByDesc(AgentWorkflowCheckpointEntity::getCheckpointVersion)
                        .last("limit 1")
        );
        if (entity == null || entity.getSnapshotJson() == null) {
            return Optional.empty();
        }
        return Optional.of(readJson(entity.getSnapshotJson()));
    }

    @Override
    public Optional<SupervisorWorkflowState> findByTraceId(String traceId) {
        AgentWorkflowCheckpointEntity entity = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentWorkflowCheckpointEntity>()
                        .eq(AgentWorkflowCheckpointEntity::getTraceId, traceId)
                        .orderByDesc(AgentWorkflowCheckpointEntity::getCheckpointVersion)
                        .last("limit 1")
        );
        if (entity == null || entity.getSnapshotJson() == null) {
            return Optional.empty();
        }
        return Optional.of(readJson(entity.getSnapshotJson()));
    }

    @Override
    public Optional<SupervisorWorkflowState> findByApprovalId(String approvalId) {
        AgentWorkflowCheckpointEntity entity = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentWorkflowCheckpointEntity>()
                        .eq(AgentWorkflowCheckpointEntity::getPendingApprovalId, approvalId)
                        .orderByDesc(AgentWorkflowCheckpointEntity::getCheckpointVersion)
                        .last("limit 1")
        );
        if (entity == null || entity.getSnapshotJson() == null) {
            return Optional.empty();
        }
        return Optional.of(readJson(entity.getSnapshotJson()));
    }

    @Override
    public void delete(String taskId) {
        checkpointMapper.delete(new LambdaQueryWrapper<AgentWorkflowCheckpointEntity>()
                .eq(AgentWorkflowCheckpointEntity::getTaskId, taskId));
    }

    private String writeJson(SupervisorWorkflowState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize workflow checkpoint", exception);
        }
    }

    private SupervisorWorkflowState readJson(String snapshotJson) {
        try {
            return objectMapper.readValue(snapshotJson, SupervisorWorkflowState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize workflow checkpoint", exception);
        }
    }
}
