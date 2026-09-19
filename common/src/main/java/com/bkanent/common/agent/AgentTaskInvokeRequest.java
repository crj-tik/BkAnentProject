package com.bkanent.common.agent;

import java.util.List;
import java.util.Map;

/**
 * AgentTaskInvokeRequest 表示 Supervisor 内部调用子 Agent 的规范化请求。
 *
 * <p>该类型只在 Supervisor 内部图、交接和重试流程中使用，不是 A2A 网络请求体。
 * 官方 A2A 适配器会将它映射为 Message/Task metadata。</p>
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
