package com.bkanent.listing.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.listing.config.ListingAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class ListingOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public ListingOfficialA2aAgent(ChatModel chatModel,
                                   ListingAgentProperties properties,
                                   @Qualifier("listingToolCallbackProvider") ToolCallbackProvider toolCallbackProvider) {
        this.reactAgent = ReactAgent.builder()
                .name("listing-agent")
                .description("Responsible for property listing search, recommendation, and summary with LLM-driven insights")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(toolCallbackProvider.getToolCallbacks())
                .interceptors(new A2aSupervisorContextInterceptor())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent listingReactAgent() {
        return reactAgent;
    }

    @Bean
    public AgentExecutor listingA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("listing"));
    }
}
