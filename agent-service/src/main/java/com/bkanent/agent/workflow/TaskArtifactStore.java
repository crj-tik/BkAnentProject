package com.bkanent.agent.workflow;

import java.util.Map;
import java.util.Optional;

/**
 * TaskArtifactStore 任务产物存储接口。
 */
public interface TaskArtifactStore {

    String save(String taskId,
                String sessionId,
                String agentId,
                String artifactType,
                Integer versionNo,
                Object content,
                Map<String, Object> metadata,
                String traceId);

    /**
     * Finds an artifact created for the same remote A2A artifact/task identity.
     * Implementations may return empty when the backing store cannot query metadata.
     */
    default Optional<String> findBySourceArtifactId(String taskId,
                                                    String sessionId,
                                                    String agentId,
                                                    String sourceArtifactId) {
        return Optional.empty();
    }
}
