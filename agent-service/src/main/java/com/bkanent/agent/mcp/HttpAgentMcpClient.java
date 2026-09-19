package com.bkanent.agent.mcp;

import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.mcp.model.AgentMcpToolDescriptor;
import com.bkanent.common.mcp.DynamicMcpClientManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HttpAgentMcpClient implements AgentMcpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentMcpClient.class);

    private final Map<String, McpSyncClient> clientsByName;
    private final ObjectMapper objectMapper;
    private final DynamicMcpClientManager dynamicMcpClientManager;

    public HttpAgentMcpClient(@Qualifier("mcpClientsByName") Map<String, McpSyncClient> clientsByName,
                              ObjectMapper objectMapper,
                              DynamicMcpClientManager dynamicMcpClientManager) {
        this.clientsByName = clientsByName == null ? Map.of() : clientsByName;
        this.objectMapper = objectMapper;
        this.dynamicMcpClientManager = dynamicMcpClientManager;
        this.dynamicMcpClientManager.reserveNames(this.clientsByName.keySet());
    }

    @Override
    public List<AgentMcpToolDescriptor> listTools() {
        List<AgentMcpToolDescriptor> descriptors = new ArrayList<>();
        for (Map.Entry<String, McpSyncClient> entry : allClients().entrySet()) {
            collectTools(entry.getKey(), entry.getValue(), descriptors);
        }
        return descriptors;
    }

    private void collectTools(String serverName, McpSyncClient client, List<AgentMcpToolDescriptor> descriptors) {
        try {
            McpSchema.ListToolsResult result = client.listTools();
            for (McpSchema.Tool tool : result.tools()) {
                descriptors.add(new AgentMcpToolDescriptor(
                        serverName,
                        tool.name(),
                        tool.description(),
                        writeJson(tool.inputSchema()),
                        writeJson(tool.outputSchema())
                ));
            }
        } catch (Exception exception) {
            log.warn("Failed to list MCP tools from '{}'", serverName, exception);
        }
    }

    @Override
    public AgentMcpCallResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpSyncClient client = getRequiredClient(serverName);
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments == null ? Map.of() : arguments));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException(extractText(result));
        }
        Map<String, Object> payload = readPayload(result.structuredContent());
        String text = payload.containsKey("resultText")
                ? String.valueOf(payload.get("resultText"))
                : extractText(result);
        return new AgentMcpCallResult(serverName, toolName, text, payload);
    }

    public Map<String, McpSyncClient> allClients() {
        Map<String, McpSyncClient> dynamic = dynamicMcpClientManager.dynamicClients();
        if (dynamic.isEmpty()) {
            return clientsByName;
        }
        Map<String, McpSyncClient> merged = new LinkedHashMap<>(clientsByName);
        dynamic.forEach(merged::putIfAbsent);
        return java.util.Collections.unmodifiableMap(merged);
    }

    private McpSyncClient getRequiredClient(String serverName) {
        McpSyncClient client = clientsByName.get(serverName);
        if (client == null) {
            client = dynamicMcpClientManager.dynamicClients().get(serverName);
        }
        if (client == null) {
            throw new IllegalArgumentException("MCP server not found: " + serverName);
        }
        return client;
    }

    private Map<String, Object> readPayload(Object structuredContent) {
        if (structuredContent == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(structuredContent, new TypeReference<>() {
        });
    }

    private String extractText(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            builder.append(content.toString()).append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }
}
