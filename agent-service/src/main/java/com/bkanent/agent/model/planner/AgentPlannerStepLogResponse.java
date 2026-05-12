package com.bkanent.agent.model.planner;

import java.util.Map;

/**
 * Planner step log response.
 */
public record AgentPlannerStepLogResponse(
        Integer stepNo,
        String action,
        boolean success,
        boolean skipped,
        String requestContent,
        String resolvedInput,
        String outputKey,
        String outputContent,
        Map<String, Object> outputPayload,
        String errorMessage
) {
}
