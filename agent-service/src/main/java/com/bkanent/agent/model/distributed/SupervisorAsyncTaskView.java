package com.bkanent.agent.model.distributed;

public record SupervisorAsyncTaskView(
        String sessionId,
        String taskId,
        String asyncTaskId,
        String status,
        String errorMessage,
        String traceId,
        String selectedAgentId,
        String mode
) {
}
