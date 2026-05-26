package com.bkanent.common.agent;

import java.util.Map;

/**
 * SessionStreamEvent 表示主 Agent 对外的统一会话事件。
 */
public record SessionStreamEvent(
        String sessionId,
        String taskId,
        String agentId,
        String eventType,
        String content,
        Map<String, Object> metadata,
        String traceId,
        Long timestamp
) {
}
