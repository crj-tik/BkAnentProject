package com.bkanent.common.agent;

import java.util.List;
import java.util.Map;

/**
 * AgentTaskInvokeResponse 表示 Supervisor 内部规范化的子 Agent 结果。
 *
 * <p>该类型不是 A2A 网络响应体，由官方 A2A 适配器从 Message/Task/Artifact 结果生成。</p>
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
