package com.bkanent.notification.config;

import com.bkanent.notification.tool.NotificationTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotificationAgentProperties.class)
public class NotificationAgentConfig {

    @Bean("notificationToolCallbackProvider")
    public ToolCallbackProvider notificationToolCallbackProvider(NotificationTools notificationTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(notificationTools)
                .build();
    }

    @Bean("notificationChatClient")
    public ChatClient notificationChatClient(ChatModel chatModel,
                                             ToolCallbackProvider notificationToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(notificationToolCallbackProvider)
                .build();
    }
}