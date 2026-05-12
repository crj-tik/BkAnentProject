package com.bkanent.agent.model.planner;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Planner session log response.
 */
public record AgentPlannerSessionLogResponse(
        String sessionNo,
        String executionMode,
        String userMessage,
        String finalAnswer,
        String planSummary,
        AgentPlan finalPlan,
        String toolContext,
        Integer replanCount,
        boolean completed,
        LocalDateTime createdAt,
        List<AgentPlannerStepLogResponse> stepLogs
) {
}
