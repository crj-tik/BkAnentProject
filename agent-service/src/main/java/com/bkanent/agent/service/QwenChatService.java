package com.bkanent.agent.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.bkanent.agent.config.AgentQwenProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

@Service
public class QwenChatService {

    private final ChatClient chatClient;
    private final AgentQwenProperties agentQwenProperties;

    public QwenChatService(ChatModel chatModel, AgentQwenProperties agentQwenProperties) {
        this.chatClient = ChatClient.create(chatModel);
        this.agentQwenProperties = agentQwenProperties;
    }

    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, defaultOptions(agentQwenProperties.getMaxTokens()));
    }

    public String completeForDecision(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, defaultOptions(agentQwenProperties.getDecisionMaxTokens()));
    }

    public String getModel() {
        return agentQwenProperties.getModel();
    }

    private String complete(String systemPrompt, String userPrompt, DashScopeChatOptions options) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(options)
                .call()
                .content();
    }

    private DashScopeChatOptions defaultOptions(Integer maxTokens) {
        return DashScopeChatOptions.builder()
                .withModel(agentQwenProperties.getModel())
                .withTemperature(agentQwenProperties.getTemperature())
                .withMaxToken(maxTokens)
                .build();
    }
}
