package com.bkanent.agent.service;

import com.bkanent.agent.config.AgentChatProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AgentChatService {

    private final ChatClient chatClient;

    private final ChatClient baseToolChatClient;

    private final AgentChatProperties agentChatProperties;

    public AgentChatService(@Qualifier("chatClient") ChatClient chatClient,
                            @Qualifier("baseToolChatClient") ChatClient baseToolChatClient,
                            AgentChatProperties agentChatProperties) {
        this.chatClient = chatClient;
        this.baseToolChatClient = baseToolChatClient;
        this.agentChatProperties = agentChatProperties;
    }

    public String call(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, true);
    }

    public String call(String systemPrompt, String userPrompt, boolean allowMcp) {
        ChatClient client = allowMcp ? chatClient : baseToolChatClient;
        return client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(defaultOptions())
                .call()
                .content();
    }

    public String getModel() {
        return agentChatProperties.getModel();
    }

    public String getSystemPrompt() {
        return agentChatProperties.getSystemPrompt();
    }

    private ChatOptions defaultOptions() {
        DefaultChatOptions options = new DefaultChatOptions();
        options.setModel(agentChatProperties.getModel());
        options.setTemperature(agentChatProperties.getTemperature());
        options.setMaxTokens(agentChatProperties.getMaxTokens());
        if (agentChatProperties.getTopP() != null) {
            options.setTopP(agentChatProperties.getTopP());
        }
        return options;
    }
}
