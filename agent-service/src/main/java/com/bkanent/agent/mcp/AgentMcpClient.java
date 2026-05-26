package com.bkanent.agent.mcp;

import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.mcp.model.AgentMcpToolDescriptor;

import java.util.List;
import java.util.Map;

/**
 * AgentMcpClient 客户端。
 */
public interface AgentMcpClient {

    List<AgentMcpToolDescriptor> listTools();

    AgentMcpCallResult callTool(String serverName, String toolName, Map<String, Object> arguments);
}
