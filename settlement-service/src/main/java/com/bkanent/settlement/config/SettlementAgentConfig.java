package com.bkanent.settlement.config;

import com.bkanent.settlement.tool.SettlementTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SettlementAgentProperties.class)
public class SettlementAgentConfig {

    @Bean("settlementToolCallbackProvider")
    public ToolCallbackProvider settlementToolCallbackProvider(SettlementTools settlementTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(settlementTools)
                .build();
    }

    @Bean("settlementChatClient")
    public ChatClient settlementChatClient(ChatModel chatModel,
                                           ToolCallbackProvider settlementToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(settlementToolCallbackProvider)
                .build();
    }
}