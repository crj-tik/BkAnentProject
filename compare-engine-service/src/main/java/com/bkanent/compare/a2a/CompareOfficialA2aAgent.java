package com.bkanent.compare.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.SkillTool;
import com.bkanent.common.skill.runtime.SkillRoutingModelInterceptor;
import com.bkanent.common.skill.runtime.SkillRoutingToolInterceptor;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final SkillRegistry skillRegistry;
    private final List<ToolCallback> executionTools;

    public CompareOfficialA2aAgent(ChatModel chatModel,
                                   CompareAgentProperties properties,
                                   CompareTools compareTools, SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(
                MethodToolCallbackProvider.builder().toolObjects(compareTools).build().getToolCallbacks()));
        tools.add(new SkillTool(skillRegistry, "compare"));
        this.executionTools = List.copyOf(tools);
        this.reactAgent = ReactAgent.builder()
                .name("compare-agent")
                .description("Responsible for multi-listing comparison analysis with LLM-driven insights, side-by-side metrics, and AI-generated conclusions")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(tools)
                .interceptors(new A2aSupervisorContextInterceptor(),
                        new SkillRoutingModelInterceptor(skillRegistry, "compare"), new SkillRoutingToolInterceptor())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent compareReactAgent() {
        return reactAgent;
    }

    @Bean
    public io.a2a.spec.AgentCard comparePublishedAgentCard(
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties server,
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties card) {
        return com.bkanent.common.a2a.SkillAgentCardPublisher.publish(
                new com.alibaba.cloud.ai.a2a.autoconfigure.server.A2aServerAgentCardAutoConfiguration()
                        .agentCard(reactAgent, server, card), skillRegistry, "compare");
    }

    @Bean
    public AgentExecutor compareA2aAgentExecutor(ObjectMapper objectMapper, com.bkanent.common.a2a.A2aExecutionProperties execution) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("compare"),
                skillRegistry, "compare", executionTools, execution.getStreamTimeoutMs());
    }
}
