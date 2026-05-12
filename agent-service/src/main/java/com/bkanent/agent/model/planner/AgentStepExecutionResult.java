package com.bkanent.agent.model.planner;

import java.util.Map;

/**
 * Planner step execution result.
 */
public record AgentStepExecutionResult(
        Integer stepNo,
        String action,
        boolean success,
        String request,
        String resolvedInput,
        String outputKey,
        String output,
        Map<String, Object> outputPayload,
        String errorMessage,
        boolean skipped
) {
}
