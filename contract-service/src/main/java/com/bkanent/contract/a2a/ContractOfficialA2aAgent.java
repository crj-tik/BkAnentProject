package com.bkanent.contract.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.contract.config.ContractAgentProperties;
import com.bkanent.contract.tool.ContractTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class ContractOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public ContractOfficialA2aAgent(ChatModel chatModel,
                                    ContractAgentProperties properties,
                                    ContractTools contractTools) {
        this.reactAgent = ReactAgent.builder()
                .name("contract-agent")
                .description("Responsible for contract parsing, risk review, and contract lifecycle management with LLM-driven analysis")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(MethodToolCallbackProvider.builder().toolObjects(contractTools).build().getToolCallbacks())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent contractReactAgent() {
        return reactAgent;
    }
}