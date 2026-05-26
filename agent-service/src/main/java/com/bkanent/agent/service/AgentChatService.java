package com.bkanent.agent.service;

import com.bkanent.agent.config.AgentChatProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.DefaultChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentChatService {

    @Qualifier("chatClient")
    private final ChatClient chatClient;

    @Qualifier("localOnlyChatClient")
    private final ChatClient localOnlyChatClient;

    private final AgentChatProperties agentChatProperties;

    public String call(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, true);
    }

    public String call(String systemPrompt, String userPrompt, boolean allowMcp) {
        ChatClient client = allowMcp ? chatClient : localOnlyChatClient;
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
