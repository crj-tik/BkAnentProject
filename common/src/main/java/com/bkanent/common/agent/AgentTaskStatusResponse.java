package com.bkanent.common.agent;

import java.util.List;

/**
 * AgentTaskStatusResponse 表示长任务状态。
 */
public record AgentTaskStatusResponse(
        String taskId,
        String agentId,
        String status,
        Integer progressPercent,
        String message,
        List<String> artifactIds,
        String traceId
) {
}
