package com.bkanent.marketing.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.marketing.config.MarketingAgentProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class MarketingOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public MarketingOfficialA2aAgent(ChatModel chatModel,
                                     MarketingAgentProperties properties,
                                     @Qualifier("marketingToolCallbackProvider") ToolCallbackProvider toolCallbackProvider) {
        this.reactAgent = ReactAgent.builder()
                .name("marketing-agent")
                .description("Responsible for marketing copy generation, content creation, publish preparation and execution with LLM-driven creativity")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(toolCallbackProvider.getToolCallbacks())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent marketingReactAgent() {
        return reactAgent;
    }
}