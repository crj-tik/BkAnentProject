package com.bkanent.common.agent;

/**
 * A2aAsyncTaskCreateResponse 表示 Supervisor 内部保存的官方 A2A 异步任务句柄。
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
