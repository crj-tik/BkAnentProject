package com.bkanent.agent.mcp;

import com.bkanent.agent.config.AgentMcpProperties;
import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.mcp.model.AgentMcpToolDescriptor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real MCP client implementation backed by remote HTTP MCP servers.
 */
@Component
public class HttpAgentMcpClient implements AgentMcpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentMcpClient.class);

    private final AgentMcpProperties agentMcpProperties;
    private final ObjectMapper objectMapper;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public HttpAgentMcpClient(AgentMcpProperties agentMcpProperties,
                              ObjectMapper objectMapper) {
        this.agentMcpProperties = agentMcpProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AgentMcpToolDescriptor> listTools() {
        List<AgentMcpToolDescriptor> descriptors = new ArrayList<>();
        for (String serverName : agentMcpProperties.getServers().keySet()) {
            McpSchema.ListToolsResult result;
            try {
                result = getClient(serverName).listTools();
            } catch (Exception exception) {
                log.warn("Skipping unavailable MCP server {} while listing tools: {}", serverName, exception.getMessage());
                continue;
            }
            for (McpSchema.Tool tool : result.tools()) {
                descriptors.add(new AgentMcpToolDescriptor(
                        serverName,
                        tool.name(),
                        tool.description(),
                        writeJson(tool.inputSchema()),
                        writeJson(tool.outputSchema())
                ));
            }
        }
        return descriptors;
    }

    @Override
    public AgentMcpCallResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = getClient(serverName).callTool(new McpSchema.CallToolRequest(toolName, arguments));
        Map<String, Object> payload = readPayload(result.structuredContent());
        String text = payload.containsKey("resultText")
                ? String.valueOf(payload.get("resultText"))
                : extractText(result, payload);
        return new AgentMcpCallResult(serverName, toolName, text, payload);
    }

    @PreDestroy
    public void destroy() {
        for (McpSyncClient client : clients.values()) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
    }

    private McpSyncClient getClient(String serverName) {
        return clients.computeIfAbsent(serverName, this::createClient);
    }

    private McpSyncClient createClient(String serverName) {
        String endpoint = agentMcpProperties.getServers().get(serverName);
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("MCP server endpoint not configured: " + serverName);
        }
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(endpoint)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper.copy()))
                .connectTimeout(agentMcpProperties.getConnectTimeout())
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(agentMcpProperties.getRequestTimeout())
                .initializationTimeout(agentMcpProperties.getInitializationTimeout())
                .clientInfo(new McpSchema.Implementation("agent-service", "1.0.0-SNAPSHOT"))
                .build();
        client.initialize();
        return client;
    }

    private Map<String, Object> readPayload(Object structuredContent) {
        if (structuredContent == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(structuredContent, new TypeReference<>() {
        });
    }

    private String extractText(McpSchema.CallToolResult result, Map<String, Object> payload) {
        if (!payload.isEmpty()) {
            return writeJson(payload);
        }
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        return result.content().toString();
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }
}
