package com.bkanent.marketing.config;

import com.bkanent.marketing.tool.MarketingTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MarketingAgentProperties.class)
public class MarketingAgentConfig {

    @Bean("marketingToolCallbackProvider")
    public ToolCallbackProvider marketingToolCallbackProvider(MarketingTools marketingTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(marketingTools)
                .build();
    }

    @Bean("marketingChatClient")
    public ChatClient marketingChatClient(ChatModel chatModel,
                                          ToolCallbackProvider marketingToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(marketingToolCallbackProvider)
                .build();
    }
}