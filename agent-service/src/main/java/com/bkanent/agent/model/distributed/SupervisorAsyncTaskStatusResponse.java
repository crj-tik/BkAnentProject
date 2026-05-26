package com.bkanent.agent.model.distributed;

/**
 * SupervisorAsyncTaskStatusResponse 表示主 Agent 异步任务状态查询响应。
 */
public record SupervisorAsyncTaskStatusResponse(
        String sessionId,
        String taskId,
        String asyncTaskId,
        String status,
        SupervisorTaskResponse result,
        String errorMessage,
        String traceId,
        String selectedAgentId,
        String mode
) {
}
