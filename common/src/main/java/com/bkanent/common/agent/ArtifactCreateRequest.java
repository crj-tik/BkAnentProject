package com.bkanent.common.agent;

import java.util.Map;

/**
 * ArtifactCreateRequest 表示创建任务产物的请求。
 */
public record ArtifactCreateRequest(
        String taskId,
        String sessionId,
        String agentId,
        String artifactType,
        Integer version,
        Object content,
        Map<String, Object> metadata,
        String traceId
) {
}
