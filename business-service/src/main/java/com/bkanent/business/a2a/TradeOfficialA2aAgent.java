package com.bkanent.business.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.business.config.TradeAgentProperties;
import com.bkanent.business.tool.TradeTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class TradeOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public TradeOfficialA2aAgent(ChatModel chatModel,
                                  TradeAgentProperties properties,
                                  TradeTools tradeTools) {
        this.reactAgent = ReactAgent.builder()
                .name("trade-agent")
                .description("Responsible for transaction feasibility analysis and risk reasoning using LLM with business data tools")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(MethodToolCallbackProvider.builder().toolObjects(tradeTools).build().getToolCallbacks())
                .interceptors(new A2aSupervisorContextInterceptor())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent tradeReactAgent() {
        return reactAgent;
    }

    @Bean
    public AgentExecutor tradeA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("trade"));
    }
}
