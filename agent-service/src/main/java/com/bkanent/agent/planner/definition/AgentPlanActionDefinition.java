package com.bkanent.agent.planner.definition;

import com.bkanent.agent.mcp.AgentToolExecutionType;

import java.util.List;

/**
 * Planner action metadata.
 */
public record AgentPlanActionDefinition(
        String action,
        String description,
        List<String> requiredArguments,
        String inputDescription,
        String outputDescription,
        String exampleArguments,
        String inputSchema,
        String outputSchema,
        String exampleOutput,
        AgentToolExecutionType executionType,
        String mcpServerName,
        String mcpToolName
) {
}
