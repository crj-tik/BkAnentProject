package com.bkanent.common.agent;

import java.util.List;
import java.util.Map;

/**
 * AgentHandoffPacket 表示 Agent 间 handoff 载荷。
 */
public record AgentHandoffPacket(
        String sessionId,
        String taskId,
        String parentTaskId,
        String traceId,
        String fromAgent,
        String toAgent,
        String reason,
        String userGoal,
        Map<String, Object> structuredContext,
        List<String> artifactIds,
        List<String> constraints,
        String expectedOutput
) {
}
