package com.bkanent.contract.config;

import com.bkanent.contract.tool.ContractTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ContractAgentProperties.class)
public class ContractAgentConfig {

    @Bean("contractToolCallbackProvider")
    public ToolCallbackProvider contractToolCallbackProvider(ContractTools contractTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(contractTools)
                .build();
    }

    @Bean("contractChatClient")
    public ChatClient contractChatClient(ChatModel chatModel,
                                         ToolCallbackProvider contractToolCallbackProvider) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(contractToolCallbackProvider)
                .build();
    }
}