package com.bkanent.agent.mcp;

import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.mcp.model.AgentMcpToolDescriptor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HttpAgentMcpClient 客户端。
 */
@Component
public class HttpAgentMcpClient implements AgentMcpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentMcpClient.class);

    /**
     * 字段：namedMcpSyncClients。
     */
    private final List<NamedMcpSyncClient> namedMcpSyncClients;
    /**
     * 字段：objectMapper。
     */
    private final ObjectMapper objectMapper;

    /**
     * 构造 HttpAgentMcpClient 实例。
     */
    public HttpAgentMcpClient(List<NamedMcpSyncClient> namedMcpSyncClients,
                              ObjectMapper objectMapper) {
        this.namedMcpSyncClients = namedMcpSyncClients;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询tools。
     */
    @Override
    public List<AgentMcpToolDescriptor> listTools() {
        List<AgentMcpToolDescriptor> descriptors = new ArrayList<>();
        for (NamedMcpSyncClient namedClient : namedMcpSyncClients) {
            try {
                McpSchema.ListToolsResult result = namedClient.client().listTools();
                for (McpSchema.Tool tool : result.tools()) {
                    descriptors.add(new AgentMcpToolDescriptor(
                            namedClient.serverName(),
                            tool.name(),
                            tool.description(),
                            writeJson(tool.inputSchema()),
                            writeJson(tool.outputSchema())
                    ));
                }
            } catch (Exception exception) {
                log.warn("Failed to list MCP tools from '{}'", namedClient.serverName(), exception);
            }
        }
        return descriptors;
    }

    /**
     * 调用tool。
     */
    @Override
    public AgentMcpCallResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        NamedMcpSyncClient namedClient = getRequiredClient(serverName);
        McpSchema.CallToolResult result = namedClient.client().callTool(new McpSchema.CallToolRequest(toolName, arguments == null ? Map.of() : arguments));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException(extractText(result));
        }
        Map<String, Object> payload = readPayload(result.structuredContent());
        String text = payload.containsKey("resultText")
                ? String.valueOf(payload.get("resultText"))
                : extractText(result);
        return new AgentMcpCallResult(serverName, toolName, text, payload);
    }

    /**
     * 处理namedClients。
     */
    public List<NamedMcpSyncClient> namedClients() {
        return namedMcpSyncClients;
    }

    /**
     * 获取requiredClient。
     */
    private NamedMcpSyncClient getRequiredClient(String serverName) {
        return namedMcpSyncClients.stream()
                .filter(client -> client.serverName().equals(serverName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverName));
    }

    /**
     * 读取payload。
     */
    private Map<String, Object> readPayload(Object structuredContent) {
        if (structuredContent == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(structuredContent, new TypeReference<>() {
        });
    }

    /**
     * 提取text。
     */
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

    /**
     * 写入json。
     */
    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }
}
