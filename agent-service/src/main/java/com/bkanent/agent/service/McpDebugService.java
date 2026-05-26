package com.bkanent.agent.service;

import com.bkanent.agent.mcp.model.DiscoveredMcpTool;
import com.bkanent.agent.mcp.model.McpServerStatus;
import com.bkanent.agent.mcp.model.RegisteredMcpTool;

import java.util.List;

/**
 * McpDebugService 服务类。
 */
public interface McpDebugService {

    List<DiscoveredMcpTool> listDiscoveredTools();

    List<RegisteredMcpTool> listRegisteredTools();

    List<McpServerStatus> listServerStatuses();
}
