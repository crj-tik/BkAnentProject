package com.bkanent.compare.config;

import com.bkanent.compare.tool.CompareTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CompareAgentProperties.class)
public class CompareAgentConfig {

    @Bean("compareToolCallbackProvider")
    public ToolCallbackProvider compareToolCallbackProvider(CompareTools compareTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(compareTools)
                .build();
    }

    @Bean("compareChatClient")
    public ChatClient compareChatClient(ChatModel chatModel,
                                        ToolCallbackProvider compareToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(compareToolCallbackProvider)
                .build();
    }
}