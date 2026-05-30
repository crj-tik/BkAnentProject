package com.bkanent.listing.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.listing.config.ListingAgentProperties;
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
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent listingReactAgent() {
        return reactAgent;
    }
}
