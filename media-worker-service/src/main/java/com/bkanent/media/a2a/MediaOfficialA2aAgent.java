package com.bkanent.media.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.SkillRoutingModelInterceptor;
import com.bkanent.common.skill.runtime.SkillTool;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.media.config.MediaAgentProperties;
import com.bkanent.media.tool.MediaTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class MediaOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";
    private static final String DOMAIN = "media";

    private final ReactAgent reactAgent;
    private final SkillRegistry skillRegistry;
    private List<ToolCallback> executionTools;

    public MediaOfficialA2aAgent(ChatModel chatModel,
                                 MediaAgentProperties properties,
                                 MediaTools mediaTools,
                                 SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        this.reactAgent = buildAgent(chatModel, properties, mediaTools, skillRegistry);
    }

    private ReactAgent buildAgent(ChatModel chatModel, MediaAgentProperties properties,
                                  MediaTools mediaTools, SkillRegistry skillRegistry) {
        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(mediaTools).build().getToolCallbacks()));
        tools.add(new SkillTool(skillRegistry, DOMAIN));
        executionTools = List.copyOf(tools);
        return ReactAgent.builder()
                .name("media-agent")
                .description("Responsible for media task generation, video/cover asset preparation, and publish-ready media references with LLM-driven task routing")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(tools)
                .interceptors(new A2aSupervisorContextInterceptor(),
                        new SkillRoutingModelInterceptor(skillRegistry, DOMAIN),
                        new com.bkanent.common.skill.runtime.SkillRoutingToolInterceptor())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent mediaReactAgent() {
        return reactAgent;
    }

    @Bean
    public AgentExecutor mediaA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("media"),
                skillRegistry, DOMAIN, executionTools);
    }
}
