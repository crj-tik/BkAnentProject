package com.bkanent.compare.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.compare.config.CompareAgentProperties;
import com.bkanent.compare.tool.CompareTools;
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
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent compareReactAgent() {
        return reactAgent;
    }
}