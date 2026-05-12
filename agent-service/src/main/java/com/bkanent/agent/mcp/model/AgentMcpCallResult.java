package com.bkanent.agent.mcp.model;

import java.util.Map;

/**
 * Result returned from an MCP tool call.
 */
public record AgentMcpCallResult(
        String serverName,
        String toolName,
        String text,
        Map<String, Object> payload
) {
}
