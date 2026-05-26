package com.bkanent.common.agent;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * ArtifactMeta 表示任务产物元数据。
 */
public record ArtifactMeta(
        String artifactId,
        String taskId,
        String sessionId,
        String agentId,
        String artifactType,
        Integer version,
        Map<String, Object> metadata,
        String traceId,
        LocalDateTime createdAt
) {
}
