package com.bkanent.agent.model.planner;

import java.util.Map;

/**
 * Single planner step.
 */
public record AgentPlanStep(
        Integer stepNo,
        String action,
        String description,
        Integer inputFromStepNo,
        String inputKey,
        String outputKey,
        Map<String, Object> arguments
) {
}
