package com.bkanent.business.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.business.config.TradeAgentProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class TradeOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public TradeOfficialA2aAgent(ChatModel chatModel,
                                  TradeAgentProperties properties,
                                  @Qualifier("tradeToolCallbackProvider") ToolCallbackProvider toolCallbackProvider) {
        this.reactAgent = ReactAgent.builder()
                .name("trade-agent")
                .description("Responsible for transaction feasibility analysis and risk reasoning using LLM with business data tools")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(toolCallbackProvider.getToolCallbacks())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent tradeReactAgent() {
        return reactAgent;
    }
}
