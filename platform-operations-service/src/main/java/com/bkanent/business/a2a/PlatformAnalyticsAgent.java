package com.bkanent.business.a2a;

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
import com.bkanent.common.a2a.A2aMemoryAgentHook;
import com.bkanent.common.a2a.A2aMemoryHookFactory;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.business.config.PlatformOperationsAnalyticsProperties;
import com.bkanent.business.tool.AnalyticsTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

@Component
public class PlatformAnalyticsAgent {

    private static final String OUTPUT_KEY = "output";
    private static final String DOMAIN = "analytics";

    private final ReactAgent reactAgent;
    private final SkillRegistry skillRegistry;
    private final List<ToolCallback> executionTools;
    private final A2aMemoryAgentHook memoryHook;

    public PlatformAnalyticsAgent(ChatModel chatModel,
                                  PlatformOperationsAnalyticsProperties properties,
                                   AnalyticsTools analyticsTools, SkillRegistry skillRegistry,
                                   A2aMemoryHookFactory memoryHookFactory) {
        this.skillRegistry = skillRegistry;
        this.memoryHook = memoryHookFactory.create("platform-operations-service", DOMAIN,
                "platform-operations-analytics", chatModel);
        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(
                MethodToolCallbackProvider.builder().toolObjects(analyticsTools).build().getToolCallbacks()));
        tools.add(new SkillTool(skillRegistry, DOMAIN));
        this.executionTools = List.copyOf(tools);
        this.reactAgent = ReactAgent.builder()
                .name("platform-operations-analytics")
                .description("经营指标查询、分析和排名")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(tools)
                .hooks(memoryHook)
                .interceptors(new com.bkanent.common.a2a.A2aAuthenticationToolInterceptor(), new A2aSupervisorContextInterceptor(),
                        new SkillRoutingModelInterceptor(skillRegistry, DOMAIN), new SkillRoutingToolInterceptor())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    public ReactAgent analyticsReactAgent() {
        return reactAgent;
    }

    public io.a2a.spec.AgentCard analyticsPublishedAgentCard(
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties server,
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties card) {
        return com.bkanent.common.a2a.SkillAgentCardPublisher.publish(
                new com.alibaba.cloud.ai.a2a.autoconfigure.server.A2aServerAgentCardAutoConfiguration()
                        .agentCard(reactAgent, server, card), skillRegistry, DOMAIN);
    }

    public AgentExecutor analyticsA2aAgentExecutor(ObjectMapper objectMapper, com.bkanent.common.a2a.A2aExecutionProperties execution) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured(DOMAIN),
                skillRegistry, DOMAIN, executionTools, execution.getStreamTimeoutMs(), memoryHook);
    }
}
