package com.bkanent.common.agent;

/**
 * A2aAsyncTaskStatusResponse 表示异步 A2A 任务状态查询响应。
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
