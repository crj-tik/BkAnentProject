package com.bkanent.agent.model.distributed;

import java.util.List;
import java.util.Map;

/**
 * SupervisorTaskResponse 主 Agent 任务响应。
 */
public record SupervisorTaskResponse(
        String sessionId,
        String taskId,
        String status,
        String finalAnswer,
        List<String> artifactIds,
        String traceId,
        String selectedAgentId,
        Map<String, Object> governanceMetadata
) {
}
