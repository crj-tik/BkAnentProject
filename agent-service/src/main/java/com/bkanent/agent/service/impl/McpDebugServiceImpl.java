package com.bkanent.agent.service.impl;

import com.bkanent.agent.config.AgentMcpProperties;
import com.bkanent.agent.config.OfficialMcpClientConfiguration;
import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.HttpAgentMcpClient;
import com.bkanent.agent.mcp.NamedMcpSyncClient;
import com.bkanent.agent.mcp.model.DiscoveredMcpTool;
import com.bkanent.agent.mcp.model.McpServerStatus;
import com.bkanent.agent.mcp.model.RegisteredMcpTool;
import com.bkanent.agent.service.McpDebugService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
     * 字段：agentMcpProperties。
     */
    private final AgentMcpProperties agentMcpProperties;
    /**
     * 字段：officialMcpClientConfiguration。
     */
    private final OfficialMcpClientConfiguration officialMcpClientConfiguration;

    /**
     * 构造 McpDebugServiceImpl 实例。
     */
    public McpDebugServiceImpl(AgentMcpClient agentMcpClient,
                               HttpAgentMcpClient httpAgentMcpClient,
                               AgentMcpProperties agentMcpProperties,
                               OfficialMcpClientConfiguration officialMcpClientConfiguration) {
        this.agentMcpClient = agentMcpClient;
        this.httpAgentMcpClient = httpAgentMcpClient;
        this.agentMcpProperties = agentMcpProperties;
        this.officialMcpClientConfiguration = officialMcpClientConfiguration;
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
        Map<String, NamedMcpSyncClient> activeClients = httpAgentMcpClient.namedClients().stream()
                .collect(Collectors.toMap(NamedMcpSyncClient::serverName, Function.identity()));
        Map<String, String> initializationFailures = officialMcpClientConfiguration.initializationFailures();
        return agentMcpProperties.getServers().entrySet().stream()
                .map(entry -> toStatus(entry.getKey(), entry.getValue(), activeClients.get(entry.getKey()), initializationFailures))
                .toList();
    }

    /**
     * 转换status。
     */
    private McpServerStatus toStatus(String serverName,
                                     String endpoint,
                                     NamedMcpSyncClient namedClient,
                                     Map<String, String> initializationFailures) {
        if (namedClient == null) {
            return new McpServerStatus(serverName, endpoint, false, 0,
                    initializationFailures.getOrDefault(serverName, "MCP client is not initialized"));
        }
        try {
            int toolCount = namedClient.client().listTools().tools().size();
            return new McpServerStatus(namedClient.serverName(), namedClient.endpoint(), true, toolCount, null);
        } catch (Exception exception) {
            return new McpServerStatus(namedClient.serverName(), namedClient.endpoint(), false, 0, exception.getMessage());
        }
    }
}
