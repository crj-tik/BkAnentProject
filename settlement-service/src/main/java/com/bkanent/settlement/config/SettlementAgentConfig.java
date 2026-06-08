package com.bkanent.settlement.config;

import com.bkanent.common.tool.McpTool;
import com.bkanent.settlement.tool.SettlementTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(SettlementAgentProperties.class)
public class SettlementAgentConfig {

    @Bean("settlementChatClient")
    public ChatClient settlementChatClient(ChatModel chatModel, SettlementTools settlementTools) {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(settlementTools)
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