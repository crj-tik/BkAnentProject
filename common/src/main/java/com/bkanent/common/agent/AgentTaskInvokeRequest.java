package com.bkanent.common.agent;

import java.util.List;
import java.util.Map;

/**
 * AgentTaskInvokeRequest 表示主 Agent 调用子 Agent 的统一请求。
 */
public record AgentTaskInvokeRequest(
        String sessionId,
        String taskId,
        String parentTaskId,
        String traceId,
        String sourceAgentId,
        String targetAgentId,
        String intent,
        String domain,
        String instruction,
        Map<String, Object> structuredContext,
        List<String> artifactIds,
        List<String> constraints,
        String expectedOutput,
        String idempotencyKey,
        Boolean stream
) {
}
