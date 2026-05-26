package com.bkanent.agent.model.distributed;

/**
 * SupervisorAsyncTaskCreateResponse 表示主 Agent 异步任务创建响应。
 */
public record SupervisorAsyncTaskCreateResponse(
        String sessionId,
        String taskId,
        String asyncTaskId,
        String status,
        String traceId,
        String selectedAgentId,
        String mode
) {
}
