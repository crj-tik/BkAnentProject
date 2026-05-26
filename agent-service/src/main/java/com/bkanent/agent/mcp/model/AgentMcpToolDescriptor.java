package com.bkanent.agent.mcp.model;

/**
 * AgentMcpToolDescriptor 记录类型。
 */
public record AgentMcpToolDescriptor(
        String serverName,
        String toolName,
        String description,
        String inputSchema,
        String outputSchema
) {
}
