package com.bkanent.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 服务基础配置。
 */
@Configuration
@EnableConfigurationProperties({MilvusConnectionProperties.class, AgentMilvusProperties.class, AgentDeepSeekProperties.class, AgentMcpProperties.class})
public class AgentServiceConfiguration {

    @Bean
    public ChatClient chatClient(ChatModel chatModel,
                                 @Qualifier("baseToolCallbackProvider") ToolCallbackProvider baseToolCallbackProvider) {

        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(baseToolCallbackProvider)
                .build();
    }
}
