package com.bkanent.marketing.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.marketing.config.MarketingAgentProperties;
import com.bkanent.marketing.tool.MarketingTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class MarketingOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public MarketingOfficialA2aAgent(ChatModel chatModel,
                                     MarketingAgentProperties properties,
                                     MarketingTools marketingTools) {
        this.reactAgent = ReactAgent.builder()
                .name("marketing-agent")
                .description("Responsible for marketing copy generation, content creation, publish preparation and execution with LLM-driven creativity")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(MethodToolCallbackProvider.builder().toolObjects(marketingTools).build().getToolCallbacks())
                .interceptors(new A2aSupervisorContextInterceptor())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent marketingReactAgent() {
        return reactAgent;
    }

    @Bean
    public AgentExecutor marketingA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("marketing"));
    }
}
