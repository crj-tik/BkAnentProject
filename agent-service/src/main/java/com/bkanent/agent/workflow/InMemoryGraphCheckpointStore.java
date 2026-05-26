package com.bkanent.agent.workflow;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * InMemoryGraphCheckpointStore 内存版图状态存储。
 */
@Component
public class InMemoryGraphCheckpointStore implements GraphCheckpointStore {

    private final ConcurrentMap<String, SupervisorWorkflowState> checkpoints = new ConcurrentHashMap<>();

    @Override
    public void save(SupervisorWorkflowState state) {
        checkpoints.put(state.taskId(), state);
    }

    @Override
    public Optional<SupervisorWorkflowState> load(String taskId) {
        return Optional.ofNullable(checkpoints.get(taskId));
    }

    @Override
    public Optional<SupervisorWorkflowState> findByTraceId(String traceId) {
        if (traceId == null) {
            return Optional.empty();
        }
        return checkpoints.values().stream()
                .filter(state -> traceId.equals(state.traceId()))
                .findFirst();
    }

    @Override
    public Optional<SupervisorWorkflowState> findByApprovalId(String approvalId) {
        if (approvalId == null) {
            return Optional.empty();
        }
        return checkpoints.values().stream()
                .filter(state -> state.pendingApproval() != null && approvalId.equals(state.pendingApproval().approvalId()))
                .findFirst();
    }

    @Override
    public void delete(String taskId) {
        checkpoints.remove(taskId);
    }
}
