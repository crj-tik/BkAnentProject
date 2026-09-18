package com.bkanent.agent.model.distributed;

import java.util.Map;

public record SessionEventAuditView(
        String eventId,
        Long sequence,
        String sessionId,
        String taskId,
        String agentId,
        String eventType,
        String stage,
        String content,
        Map<String, Object> metadata,
        String traceId,
        Long timestamp
) {

    public SessionEventAuditView(String sessionId,
                                 String taskId,
                                 String agentId,
                                 String eventType,
                                 String stage,
                                 String content,
                                 Map<String, Object> metadata,
                                 String traceId,
                                 Long timestamp) {
        this(null, null, sessionId, taskId, agentId, eventType, stage, content, metadata, traceId, timestamp);
    }
}
