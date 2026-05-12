package com.bkanent.agent.mcp.model;

/**
 * MCP tool metadata exposed by a server.
 */
public record AgentMcpToolDescriptor(
        String serverName,
        String toolName,
        String description,
        String inputSchema,
        String outputSchema
) {
}
