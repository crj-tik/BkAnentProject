package com.bkanent.agent.config;

import com.bkanent.agent.mcp.NamedMcpSyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OfficialMcpClientConfiguration 配置类。
 */
@Configuration
public class OfficialMcpClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OfficialMcpClientConfiguration.class);

    private final List<McpSyncClient> closeableClients = new ArrayList<>();
    private final Map<String, String> initializationFailures = new LinkedHashMap<>();

    /**
     * 处理namedMcpSyncClients。
     */
    @Bean
    public List<NamedMcpSyncClient> namedMcpSyncClients(AgentMcpProperties agentMcpProperties,
                                                        ObjectMapper objectMapper) {
        List<NamedMcpSyncClient> clients = new ArrayList<>();
        for (Map.Entry<String, String> entry : agentMcpProperties.getServers().entrySet()) {
            try {
                McpSyncClient client = createClient(entry.getKey(), entry.getValue(), agentMcpProperties, objectMapper);
                clients.add(new NamedMcpSyncClient(entry.getKey(), entry.getValue(), client));
                closeableClients.add(client);
            } catch (Exception exception) {
                initializationFailures.put(entry.getKey(), exception.getMessage());
                log.warn("Failed to initialize MCP client '{}' at '{}'", entry.getKey(), entry.getValue(), exception);
            }
        }
        return List.copyOf(clients);
    }

    /**
     * 处理mcpSyncClients。
     */
    @Bean
    public List<McpSyncClient> mcpSyncClients(List<NamedMcpSyncClient> namedMcpSyncClients) {
        return namedMcpSyncClients.stream()
                .map(NamedMcpSyncClient::client)
                .toList();
    }

    /**
     * 处理mcpToolCallbackProvider。
     */
    @Bean("mcpToolCallbackProvider")
    public ToolCallbackProvider mcpToolCallbackProvider(List<McpSyncClient> mcpSyncClients) {
        SyncMcpToolCallbackProvider delegate = new SyncMcpToolCallbackProvider(mcpSyncClients);
        return () -> {
            try {
                return delegate.getToolCallbacks();
            } catch (Exception exception) {
                log.warn("Failed to resolve MCP tool callbacks", exception);
                return new ToolCallback[0];
            }
        };
    }

    /**
     * 获取initializationFailures。
     */
    public Map<String, String> initializationFailures() {
        return Map.copyOf(initializationFailures);
    }

    /**
     * 销毁。
     */
    @PreDestroy
    public void destroy() {
        for (McpSyncClient client : closeableClients) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
            }
        }
        closeableClients.clear();
    }

    /**
     * 创建client。
     */
    private McpSyncClient createClient(String serverName,
                                       String endpoint,
                                       AgentMcpProperties agentMcpProperties,
                                       ObjectMapper objectMapper) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper.copy()))
                .connectTimeout(agentMcpProperties.getConnectTimeout())
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(agentMcpProperties.getRequestTimeout())
                .initializationTimeout(agentMcpProperties.getInitializationTimeout())
                .clientInfo(new McpSchema.Implementation(serverName, "1.0.0-SNAPSHOT"))
                .build();
        client.initialize();
        return client;
    }
}
