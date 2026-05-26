package com.bkanent.agent.mcp.model;

/**
 * RegisteredMcpTool 工具类。
 */
public record RegisteredMcpTool(
        String serverName,
        String toolName,
        String registeredToolName,
        String description,
        String inputSchema
) {
}
