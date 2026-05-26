package com.bkanent.agent.service.impl;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.HttpAgentMcpClient;
import com.bkanent.agent.mcp.NamedMcpSyncClient;
import com.bkanent.agent.mcp.model.DiscoveredMcpTool;
import com.bkanent.agent.mcp.model.McpServerStatus;
import com.bkanent.agent.mcp.model.RegisteredMcpTool;
import com.bkanent.agent.service.McpDebugService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * McpDebugServiceImpl 服务实现类。
 */
@Service
public class McpDebugServiceImpl implements McpDebugService {

    /**
     * 字段：agentMcpClient。
     */
    private final AgentMcpClient agentMcpClient;
    /**
     * 字段：httpAgentMcpClient。
     */
    private final HttpAgentMcpClient httpAgentMcpClient;

    /**
     * 构造 McpDebugServiceImpl 实例。
     */
    public McpDebugServiceImpl(AgentMcpClient agentMcpClient,
                               HttpAgentMcpClient httpAgentMcpClient) {
        this.agentMcpClient = agentMcpClient;
        this.httpAgentMcpClient = httpAgentMcpClient;
    }

    /**
     * 查询discoveredTools。
     */
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

    /**
     * 查询registeredTools。
     */
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

    /**
     * 查询serverStatuses。
     */
    @Override
    public List<McpServerStatus> listServerStatuses() {
        return httpAgentMcpClient.namedClients().stream()
                .map(this::toStatus)
                .toList();
    }

    /**
     * 转换status。
     */
    private McpServerStatus toStatus(NamedMcpSyncClient namedClient) {
        try {
            int toolCount = namedClient.client().listTools().tools().size();
            return new McpServerStatus(namedClient.serverName(), namedClient.endpoint(), true, toolCount, null);
        } catch (Exception exception) {
            return new McpServerStatus(namedClient.serverName(), namedClient.endpoint(), false, 0, exception.getMessage());
        }
    }
}
