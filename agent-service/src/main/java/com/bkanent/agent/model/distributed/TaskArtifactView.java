package com.bkanent.agent.model.distributed;

import java.time.LocalDateTime;

public record TaskArtifactView(
        String artifactId,
        String taskId,
        String sessionId,
        String agentId,
        String artifactType,
        Integer versionNo,
        String contentJson,
        String metadataJson,
        LocalDateTime createdAt
) {
}
