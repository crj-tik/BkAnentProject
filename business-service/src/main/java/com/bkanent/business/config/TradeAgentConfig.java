package com.bkanent.business.config;

import com.bkanent.business.tool.TradeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TradeAgentProperties.class)
public class TradeAgentConfig {

    @Bean("tradeToolCallbackProvider")
    public ToolCallbackProvider tradeToolCallbackProvider(TradeTools tradeTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tradeTools)
                .build();
    }

    @Bean("tradeChatClient")
    public ChatClient tradeChatClient(ChatModel chatModel,
                                       ToolCallbackProvider tradeToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(tradeToolCallbackProvider)
                .build();
    }
}