package com.bkanent.media.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.media.config.MediaAgentProperties;
import com.bkanent.media.tool.MediaTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class MediaOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public MediaOfficialA2aAgent(ChatModel chatModel,
                                 MediaAgentProperties properties,
                                 MediaTools mediaTools) {
        this.reactAgent = ReactAgent.builder()
                .name("media-agent")
                .description("Responsible for media task generation, video/cover asset preparation, and publish-ready media references with LLM-driven task routing")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(MethodToolCallbackProvider.builder().toolObjects(mediaTools).build().getToolCallbacks())
                .interceptors(new A2aSupervisorContextInterceptor())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent mediaReactAgent() {
        return reactAgent;
    }

    @Bean
    public AgentExecutor mediaA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("media"));
    }
}
