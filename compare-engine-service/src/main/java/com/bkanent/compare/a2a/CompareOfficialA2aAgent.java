package com.bkanent.compare.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.compare.config.CompareAgentProperties;
import com.bkanent.compare.tool.CompareTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class CompareOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public CompareOfficialA2aAgent(ChatModel chatModel,
                                   CompareAgentProperties properties,
                                   CompareTools compareTools) {
        this.reactAgent = ReactAgent.builder()
                .name("compare-agent")
                .description("Responsible for multi-listing comparison analysis with LLM-driven insights, side-by-side metrics, and AI-generated conclusions")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(MethodToolCallbackProvider.builder().toolObjects(compareTools).build().getToolCallbacks())
                .interceptors(new A2aSupervisorContextInterceptor())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent compareReactAgent() {
        return reactAgent;
    }

    @Bean
    public AgentExecutor compareA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("compare"));
    }
}
