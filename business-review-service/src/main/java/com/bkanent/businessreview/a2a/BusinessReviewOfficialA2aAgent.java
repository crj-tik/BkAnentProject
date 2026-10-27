package com.bkanent.businessreview.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.businessreview.tool.ReviewDraftTools;
import com.bkanent.common.a2a.A2aMemoryAgentHook;
import com.bkanent.common.a2a.A2aMemoryHookFactory;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.common.a2a.SkillAgentCardPublisher;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.SkillRoutingModelInterceptor;
import com.bkanent.common.skill.runtime.SkillRoutingToolInterceptor;
import com.bkanent.common.skill.runtime.SkillTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** The Agent may draft text; all state-changing work is routed through explicit user APIs. */
@Component
public class BusinessReviewOfficialA2aAgent {
    public static final String DOMAIN = "business-review";
    public static final String AGENT_ID = "business-review-agent";
    private final ReactAgent agent;
    private final SkillRegistry skills;
    private final List<ToolCallback> executionTools;
    private final A2aMemoryAgentHook memoryHook;

    public BusinessReviewOfficialA2aAgent(ChatModel chatModel, BusinessReviewAgentProperties properties,
                                          ReviewDraftTools draftTools, SkillRegistry skills,
                                          A2aMemoryHookFactory memoryHookFactory) {
        this.skills = skills;
        this.memoryHook = memoryHookFactory.create("business-review-service", DOMAIN, AGENT_ID, chatModel);
        List<ToolCallback> tools = new ArrayList<>(List.of(
                MethodToolCallbackProvider.builder().toolObjects(draftTools).build().getToolCallbacks()));
        tools.add(new SkillTool(skills, DOMAIN));
        this.executionTools = List.copyOf(tools);
        this.agent = ReactAgent.builder()
                .name(AGENT_ID)
                .description("根据已授权资料形成有来源的门店经营复盘草稿，不执行或确认计划")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(tools)
                .hooks(memoryHook)
                .interceptors(new com.bkanent.common.a2a.A2aAuthenticationToolInterceptor(),
                        new SkillRoutingModelInterceptor(skills, DOMAIN), new SkillRoutingToolInterceptor())
                .outputKey("output")
                .build();
    }
    @Bean public ReactAgent businessReviewReactAgent() { return agent; }
    @Bean
    public io.a2a.spec.AgentCard businessReviewPublishedAgentCard(
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties server,
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties card) {
        return SkillAgentCardPublisher.publish(
                new com.alibaba.cloud.ai.a2a.autoconfigure.server.A2aServerAgentCardAutoConfiguration()
                        .agentCard(agent, server, card), skills, DOMAIN);
    }
    @Bean
    public AgentExecutor businessReviewA2aAgentExecutor(ObjectMapper mapper,
            com.bkanent.common.a2a.A2aExecutionProperties execution) {
        return new OfficialA2aAgentExecutor(agent, mapper, A2aOutputPolicy.structured(DOMAIN),
                skills, DOMAIN, executionTools, execution.getStreamTimeoutMs(), memoryHook);
    }
}
