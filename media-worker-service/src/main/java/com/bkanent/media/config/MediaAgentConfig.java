package com.bkanent.media.config;

import com.bkanent.media.tool.MediaTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MediaAgentProperties.class)
public class MediaAgentConfig {

    @Bean("mediaToolCallbackProvider")
    public ToolCallbackProvider mediaToolCallbackProvider(MediaTools mediaTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(mediaTools)
                .build();
    }

    @Bean("mediaChatClient")
    public ChatClient mediaChatClient(ChatModel chatModel,
                                      ToolCallbackProvider mediaToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(mediaToolCallbackProvider)
                .build();
    }
}