package com.bkanent.notification.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.SkillRoutingModelInterceptor;
import com.bkanent.common.skill.runtime.SkillTool;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.notification.config.NotificationAgentProperties;
import com.bkanent.notification.tool.NotificationTools;
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
public class NotificationOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";
    private static final String DOMAIN = "notification";

    private final ReactAgent reactAgent;
    private final SkillRegistry skillRegistry;
    private List<ToolCallback> executionTools;

    public NotificationOfficialA2aAgent(ChatModel chatModel,
                                        NotificationAgentProperties properties,
                                        NotificationTools notificationTools,
                                        SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        this.reactAgent = buildAgent(chatModel, properties, notificationTools, skillRegistry);
    }

    private ReactAgent buildAgent(ChatModel chatModel, NotificationAgentProperties properties,
                                  NotificationTools notificationTools, SkillRegistry skillRegistry) {
        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(notificationTools).build().getToolCallbacks()));
        tools.add(new SkillTool(skillRegistry, DOMAIN));
        executionTools = List.copyOf(tools);
        return ReactAgent.builder()
                .name("notification-agent")
                .description("Responsible for in-app station messages, email notifications, and message management with LLM-driven routing")
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
    public ReactAgent notificationReactAgent() {
        return reactAgent;
    }

    @Bean
    public io.a2a.spec.AgentCard notificationPublishedAgentCard(
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties server,
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties card) {
        return com.bkanent.common.a2a.SkillAgentCardPublisher.publish(
                new com.alibaba.cloud.ai.a2a.autoconfigure.server.A2aServerAgentCardAutoConfiguration()
                        .agentCard(reactAgent, server, card), skillRegistry, "notification");
    }

    @Bean
    public AgentExecutor notificationA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("notification"),
                skillRegistry, DOMAIN, executionTools);
    }
}
