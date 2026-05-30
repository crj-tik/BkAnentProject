package com.bkanent.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.Arrays;

@Configuration
@EnableConfigurationProperties({
        MilvusConnectionProperties.class,
        AgentMilvusProperties.class,
        AgentChatProperties.class,
        AgentMcpProperties.class,
        ListingRagProperties.class,
        DistributedAgentProperties.class,
        MemoryServiceProperties.class
})
/**
 * AgentServiceConfiguration 配置类。
 */
public class AgentServiceConfiguration {

    @Bean("combinedToolCallbackProvider")
    public ToolCallbackProvider combinedToolCallbackProvider(
            @Qualifier("localToolCallbackProvider") ToolCallbackProvider localToolCallbackProvider,
            @Qualifier("mcpToolCallbackProvider") ToolCallbackProvider mcpToolCallbackProvider) {
        return () -> {
            org.springframework.ai.tool.ToolCallback[] localCallbacks = localToolCallbackProvider.getToolCallbacks();
            org.springframework.ai.tool.ToolCallback[] mcpCallbacks = mcpToolCallbackProvider.getToolCallbacks();
            org.springframework.ai.tool.ToolCallback[] combined = Arrays.copyOf(localCallbacks, localCallbacks.length + mcpCallbacks.length);
            System.arraycopy(mcpCallbacks, 0, combined, localCallbacks.length, mcpCallbacks.length);
            return combined;
        };
    }

    /**
     * 处理对话client。
     */
    @Bean("chatClient")
    public ChatClient chatClient(ChatModel chatModel,
                                 @Qualifier("combinedToolCallbackProvider") ToolCallbackProvider combinedToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(combinedToolCallbackProvider)
                .build();
    }

    /**
     * 处理baseToolChatClient。
     */
    @Bean("localToolChatClient")
    public ChatClient localToolChatClient(ChatModel chatModel,
                                         @Qualifier("localToolCallbackProvider") ToolCallbackProvider localToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(localToolCallbackProvider)
                .build();
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
