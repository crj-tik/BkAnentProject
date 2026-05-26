package com.bkanent.agent.mcp.model;

import java.util.Map;

/**
 * AgentMcpCallResult 数据对象。
 */
public record AgentMcpCallResult(
        String serverName,
        String toolName,
        String text,
        Map<String, Object> payload
) {
}
