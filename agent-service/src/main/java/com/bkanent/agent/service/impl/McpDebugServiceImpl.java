package com.bkanent.agent.service.impl;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.HttpAgentMcpClient;
import com.bkanent.agent.mcp.model.DiscoveredMcpTool;
import com.bkanent.agent.mcp.model.McpServerStatus;
import com.bkanent.agent.mcp.model.RegisteredMcpTool;
import com.bkanent.agent.service.McpDebugService;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class McpDebugServiceImpl implements McpDebugService {

    private final AgentMcpClient agentMcpClient;
    private final HttpAgentMcpClient httpAgentMcpClient;

    public McpDebugServiceImpl(AgentMcpClient agentMcpClient,
                               HttpAgentMcpClient httpAgentMcpClient) {
        this.agentMcpClient = agentMcpClient;
        this.httpAgentMcpClient = httpAgentMcpClient;
    }

    @Override
    public List<DiscoveredMcpTool> listDiscoveredTools() {
        return agentMcpClient.listTools().stream()
                .map(tool -> new DiscoveredMcpTool(
                        tool.serverName(),
                        tool.toolName(),
                        tool.description(),
                        tool.inputSchema(),
                        tool.outputSchema()))
                .toList();
    }

    @Override
    public List<RegisteredMcpTool> listRegisteredTools() {
        return agentMcpClient.listTools().stream()
                .map(tool -> new RegisteredMcpTool(
                        tool.serverName(),
                        tool.toolName(),
                        tool.toolName(),
                        tool.description(),
                        tool.inputSchema()))
                .toList();
    }

    @Override
    public List<McpServerStatus> listServerStatuses() {
        Map<String, McpSyncClient> clients = httpAgentMcpClient.clientsByName();
        return clients.entrySet().stream()
                .map(entry -> toStatus(entry.getKey(), entry.getValue()))
                .toList();
    }

    private McpServerStatus toStatus(String serverName, McpSyncClient client) {
        if (client == null) {
            return new McpServerStatus(serverName, null, false, 0,
                    "MCP client is not initialized");
        }
        try {
            int toolCount = client.listTools().tools().size();
            return new McpServerStatus(serverName, null, true, toolCount, null);
        } catch (Exception exception) {
            return new McpServerStatus(serverName, null, false, 0, exception.getMessage());
        }
    }
}
