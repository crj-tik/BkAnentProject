package com.bkanent.common.agent;

import java.util.List;
import java.util.Map;

/**
 * AgentTaskInvokeResponse 表示子 Agent 的统一响应。
 */
public record AgentTaskInvokeResponse(
        String sessionId,
        String taskId,
        String agentId,
        String status,
        Map<String, Object> structuredOutput,
        List<String> artifactIds,
        List<String> nextHints,
        String summary,
        String traceId
) {
}
