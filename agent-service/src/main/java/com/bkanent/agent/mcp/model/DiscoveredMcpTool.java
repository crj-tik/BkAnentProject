package com.bkanent.agent.mcp.model;

/**
 * DiscoveredMcpTool 工具类。
 */
public record DiscoveredMcpTool(
        String serverName,
        String toolName,
        String originalDescription,
        String inputSchema,
        String outputSchema
) {
}
