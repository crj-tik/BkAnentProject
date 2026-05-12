package com.bkanent.agent.mcp;

import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.mcp.model.AgentMcpToolDescriptor;

import java.util.List;
import java.util.Map;

/**
 * MCP client abstraction used by agent tools and planner.
 */
public interface AgentMcpClient {

    List<AgentMcpToolDescriptor> listTools();

    AgentMcpCallResult callTool(String serverName, String toolName, Map<String, Object> arguments);
}
