package com.bkanent.notification.config;

import com.bkanent.common.tool.McpTool;
import com.bkanent.notification.tool.NotificationTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(NotificationAgentProperties.class)
public class NotificationAgentConfig {

    @Bean("notificationChatClient")
    public ChatClient notificationChatClient(ChatModel chatModel, NotificationTools notificationTools) {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(notificationTools)
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