package com.bkanent.agent.mcp.model;

/**
 * AgentToolCatalogItem 数据对象。
 */
public record AgentToolCatalogItem(
        String serverName,
        String toolName,
        String originalDescription,
        String inputSchema,
        String outputSchema,
        boolean discoveredFromMcp,
        boolean registeredToLlm,
        String registeredToolName,
        String registrationMode,
        String unavailableReason
) {
}
