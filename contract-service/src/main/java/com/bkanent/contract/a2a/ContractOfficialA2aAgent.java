package com.bkanent.contract.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.contract.config.ContractAgentProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class ContractOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public ContractOfficialA2aAgent(ChatModel chatModel,
                                    ContractAgentProperties properties,
                                    @Qualifier("contractToolCallbackProvider") ToolCallbackProvider toolCallbackProvider) {
        this.reactAgent = ReactAgent.builder()
                .name("contract-agent")
                .description("Responsible for contract parsing, risk review, and contract lifecycle management with LLM-driven analysis")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(toolCallbackProvider.getToolCallbacks())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent contractReactAgent() {
        return reactAgent;
    }
}