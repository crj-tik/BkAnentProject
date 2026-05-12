package com.bkanent.agent.model.planner;

import java.util.List;

/**
 * Planner execution session.
 */
public record AgentPlannerSession(
        String sessionNo,
        AgentPlan finalPlan,
        List<AgentStepExecutionResult> executionResults,
        Integer replanCount,
        boolean completed
) {
}
