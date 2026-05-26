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
 * DbGraphCheckpointStore 数据库版检查点存储。
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
                        .last("limit 1")
        );
        if (entity == null) {
            entity = new AgentWorkflowCheckpointEntity();
            entity.setTaskId(state.taskId());
        }
        entity.setSessionId(state.sessionId());
        entity.setTraceId(state.traceId());
        entity.setWorkflowStatus(state.workflowStatus().name());
        entity.setSelectedAgentId(state.selectedAgentId());
        entity.setPendingApprovalId(state.pendingApproval() == null ? null : state.pendingApproval().approvalId());
        entity.setSnapshotJson(writeJson(state));
        if (entity.getId() == null) {
            checkpointMapper.insert(entity);
            return;
        }
        checkpointMapper.updateById(entity);
    }

    @Override
    public Optional<SupervisorWorkflowState> load(String taskId) {
        AgentWorkflowCheckpointEntity entity = checkpointMapper.selectOne(
                new LambdaQueryWrapper<AgentWorkflowCheckpointEntity>()
                        .eq(AgentWorkflowCheckpointEntity::getTaskId, taskId)
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
