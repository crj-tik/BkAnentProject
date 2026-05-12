package com.bkanent.agent.service;

import com.bkanent.agent.config.AgentDeepSeekProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.stereotype.Service;

/**
 * DeepSeek chat service.
 */
@Service
@RequiredArgsConstructor
public class DeepSeekChatService {

    private final ChatClient chatClient;
    private final AgentDeepSeekProperties agentDeepSeekProperties;

    public String call(String systemPrompt, String userPrompt, Object... tools) {
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(defaultOptions());
        if (tools != null && tools.length > 0) {
            requestSpec = requestSpec.tools(tools);
        }
        return requestSpec.call().content();
    }

    public String getModel() {
        return agentDeepSeekProperties.getModel();
    }

    public String getSystemPrompt() {
        return agentDeepSeekProperties.getSystemPrompt();
    }

    private DeepSeekChatOptions defaultOptions() {
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
                .model(agentDeepSeekProperties.getModel())
                .temperature(agentDeepSeekProperties.getTemperature())
                .maxTokens(agentDeepSeekProperties.getMaxTokens());
        if (agentDeepSeekProperties.getTopP() != null) {
            builder.topP(agentDeepSeekProperties.getTopP());
        }
        return builder.build();
    }
}
