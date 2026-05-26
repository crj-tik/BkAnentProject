package com.bkanent.agent.model.distributed;

public record SupervisorAsyncWorkflowCreateResponse(
        String sessionId,
        String taskId,
        String asyncWorkflowId,
        String status,
        String traceId
) {
}
