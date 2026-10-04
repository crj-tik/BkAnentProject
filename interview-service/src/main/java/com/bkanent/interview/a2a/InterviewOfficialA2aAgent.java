package com.bkanent.interview.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.SkillRoutingModelInterceptor;
import com.bkanent.common.skill.runtime.SkillTool;
import com.bkanent.interview.config.InterviewAgentProperties;
import com.bkanent.interview.tool.InterviewTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class InterviewOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";
    private static final String DOMAIN = "interview";

    private final ReactAgent reactAgent;
    private final SkillRegistry skillRegistry;
    private List<ToolCallback> executionTools;

    public InterviewOfficialA2aAgent(ChatModel chatModel,
                                     InterviewAgentProperties properties,
                                     InterviewTools interviewTools,
                                     SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(
                MethodToolCallbackProvider.builder().toolObjects(interviewTools).build().getToolCallbacks()));
        tools.add(new SkillTool(skillRegistry, DOMAIN));
        executionTools = List.copyOf(tools);

        this.reactAgent = ReactAgent.builder()
                .name("interview-agent")
                .description("AI deep-interview subAgent: pre-interview question design, live interview runtime support, transcript archiving, and case card reports")
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
    public ReactAgent interviewReactAgent() {
        return reactAgent;
    }

    @Bean
    public io.a2a.spec.AgentCard interviewPublishedAgentCard(
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties server,
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties card) {
        return com.bkanent.common.a2a.SkillAgentCardPublisher.publish(
                new com.alibaba.cloud.ai.a2a.autoconfigure.server.A2aServerAgentCardAutoConfiguration()
                        .agentCard(reactAgent, server, card), skillRegistry, "interview");
    }

    @Bean
    public AgentExecutor interviewA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("interview"),
                skillRegistry, DOMAIN, executionTools);
    }
}
