package com.bkanent.agent.model.distributed;

public record SupervisorAsyncWorkflowView(
        String sessionId,
        String taskId,
        String asyncWorkflowId,
        String status,
        String errorMessage,
        String traceId
) {
}
