package com.bkanent.common.agent;

/**
 * AgentErrorResponse 表示 A2A 调用错误。
 */
public record AgentErrorResponse(
        String taskId,
        String agentId,
        String errorCode,
        String errorMessage,
        Boolean retryable,
        String traceId
) {
}
