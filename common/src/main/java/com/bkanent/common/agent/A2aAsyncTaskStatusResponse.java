package com.bkanent.common.agent;

/**
 * A2aAsyncTaskStatusResponse 表示 Supervisor 内部规范化的官方 A2A 任务状态。
 */
public record A2aAsyncTaskStatusResponse(
        String sessionId,
        String taskId,
        String agentId,
        String asyncTaskId,
        String status,
        AgentTaskInvokeResponse result,
        String errorCode,
        String errorMessage,
        String traceId
) {
}
