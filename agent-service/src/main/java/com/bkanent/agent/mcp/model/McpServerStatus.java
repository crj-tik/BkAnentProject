package com.bkanent.agent.mcp.model;

/**
 * McpServerStatus 数据对象。
 */
public record McpServerStatus(
        String serverName,
        String endpoint,
        boolean available,
        int discoveredToolCount,
        String errorMessage
) {
}
