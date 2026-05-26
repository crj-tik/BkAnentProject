package com.bkanent.agent.workflow;

import java.util.Optional;

/**
 * GraphCheckpointStore 图状态存储接口。
 */
public interface GraphCheckpointStore {

    void save(SupervisorWorkflowState state);

    Optional<SupervisorWorkflowState> load(String taskId);

    Optional<SupervisorWorkflowState> findByTraceId(String traceId);

    Optional<SupervisorWorkflowState> findByApprovalId(String approvalId);

    void delete(String taskId);
}
