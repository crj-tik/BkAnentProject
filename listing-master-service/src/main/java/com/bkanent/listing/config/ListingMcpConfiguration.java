package com.bkanent.listing.config;

import com.bkanent.common.tool.McpTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Exposes listing MCP tools only together with the locally verified server guard.
 * When mcp.authorization.server.enabled is false, no tool is registered and the
 * endpoint exposes no listing capability (InterviewMcpConfiguration pattern).
 */
@Configuration(proxyBeanMethods = false)
public class ListingMcpConfiguration {

    @Bean("mcpToolCallbackProvider")
    @ConditionalOnProperty(prefix = "mcp.authorization.server", name = "enabled", havingValue = "true")
    public ToolCallbackProvider mcpToolCallbackProvider(List<McpTool> mcpTools) {
        if (mcpTools == null || mcpTools.isEmpty()) return ToolCallbackProvider.from();
        return MethodToolCallbackProvider.builder()
                .toolObjects(mcpTools.toArray())
                .build();
    }
}
