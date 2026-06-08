package com.bkanent.settlement.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.settlement.config.SettlementAgentProperties;
import com.bkanent.settlement.tool.SettlementTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class SettlementOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public SettlementOfficialA2aAgent(ChatModel chatModel,
                                      SettlementAgentProperties properties,
                                      SettlementTools settlementTools) {
        this.reactAgent = ReactAgent.builder()
                .name("settlement-agent")
                .description("Responsible for settlement calculation, commission computation, payout batch preparation, and monthly summary analysis with LLM-driven reasoning")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(MethodToolCallbackProvider.builder().toolObjects(settlementTools).build().getToolCallbacks())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent settlementReactAgent() {
        return reactAgent;
    }
}