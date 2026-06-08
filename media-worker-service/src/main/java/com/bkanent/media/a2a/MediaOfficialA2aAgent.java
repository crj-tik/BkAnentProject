package com.bkanent.media.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.media.config.MediaAgentProperties;
import com.bkanent.media.tool.MediaTools;
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
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent mediaReactAgent() {
        return reactAgent;
    }
}