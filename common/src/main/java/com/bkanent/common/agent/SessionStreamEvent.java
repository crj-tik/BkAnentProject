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
        Long timestamp,
        String eventId,
        Long sequence,
        String childRunId,
        String parentTaskId,
        String branchId,
        String phase,
        Boolean terminal,
        String visibility
) {

    /**
     * Backward-compatible constructor for existing Supervisor event producers.
     */
    public SessionStreamEvent(String sessionId,
                              String taskId,
                              String agentId,
                              String eventType,
                              String content,
                              Map<String, Object> metadata,
                              String traceId,
                              Long timestamp) {
        this(sessionId, taskId, agentId, eventType, content, metadata, traceId, timestamp,
                null, null, null, null, null, null, null, null);
    }
}
