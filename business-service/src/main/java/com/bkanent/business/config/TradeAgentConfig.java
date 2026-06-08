package com.bkanent.business.config;

import com.bkanent.business.tool.TradeTools;
import com.bkanent.common.tool.McpTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(TradeAgentProperties.class)
public class TradeAgentConfig {

    @Bean("tradeChatClient")
    public ChatClient tradeChatClient(ChatModel chatModel, TradeTools tradeTools) {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(tradeTools)
                .build();
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(provider)
                .build();
    }

    @Bean("mcpToolCallbackProvider")
    public ToolCallbackProvider mcpToolCallbackProvider(List<McpTool> mcpTools) {
        if (mcpTools.isEmpty()) {
            return ToolCallbackProvider.from();
        }
        return MethodToolCallbackProvider.builder()
                .toolObjects(mcpTools.toArray())
                .build();
    }
}