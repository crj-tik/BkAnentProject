package com.bkanent.common.agent;

import java.util.List;
import java.util.Map;

/**
 * AgentResult 表示子 Agent 的统一结构化结果。
 */
public record AgentResult(
        String taskId,
        String agentId,
        String status,
        Map<String, Object> structuredOutput,
        List<String> artifactIds,
        List<String> nextHints,
        String traceId
) {
}
