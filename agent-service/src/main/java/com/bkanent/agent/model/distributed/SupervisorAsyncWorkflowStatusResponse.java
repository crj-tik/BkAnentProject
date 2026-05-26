package com.bkanent.agent.model.distributed;

public record SupervisorAsyncWorkflowStatusResponse(
        String sessionId,
        String taskId,
        String asyncWorkflowId,
        String status,
        SupervisorTaskResponse result,
        String errorMessage,
        String traceId
) {
}
