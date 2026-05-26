package com.bkanent.agent.workflow;

import java.util.Map;

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
}
