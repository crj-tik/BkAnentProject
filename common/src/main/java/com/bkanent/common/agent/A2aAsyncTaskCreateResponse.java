package com.bkanent.common.agent;

/**
 * A2aAsyncTaskCreateResponse 表示异步 A2A 任务创建响应。
 */
public record A2aAsyncTaskCreateResponse(
        String sessionId,
        String taskId,
        String agentId,
        String asyncTaskId,
        String status,
        String traceId
) {
}
