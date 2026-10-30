package com.bkanent.knowledge.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.a2a.A2aMemoryAgentHook;
import com.bkanent.common.a2a.A2aMemoryHookFactory;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.SkillTool;
import com.bkanent.knowledge.tool.KnowledgeAgentTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeOfficialA2aAgent {
    private static final String DOMAIN = "knowledge";
    private final ReactAgent reactAgent;
    private final SkillRegistry skillRegistry;
    private final List<ToolCallback> executionTools;
    private final A2aMemoryAgentHook memoryHook;

    public KnowledgeOfficialA2aAgent(ChatModel chatModel, KnowledgeAgentTools tools, SkillRegistry skillRegistry,
                                     A2aMemoryHookFactory memoryHookFactory, ObjectMapper objectMapper) {
        this.skillRegistry = skillRegistry;
        this.memoryHook = memoryHookFactory.create("platform-operations-service", DOMAIN,
                "platform-operations-knowledge", chatModel);
        List<ToolCallback> callbacks = new ArrayList<>(List.of(MethodToolCallbackProvider.builder()
                .toolObjects(tools).build().getToolCallbacks()));
        callbacks.add(new SkillTool(skillRegistry, DOMAIN));
        this.executionTools = List.copyOf(callbacks);
        this.reactAgent = ReactAgent.builder()
                .name("platform-operations-knowledge")
                .description("知识案例生成、审核、发布和撤回")
                .model(chatModel)
                .systemPrompt("Operate only on Knowledge case IDs, Interview asset IDs, revisions and task statuses. Never request, quote, repeat, summarize verbatim, or return transcript text. A tool response contains status only. Do not claim simulated or unknown evidence is real or verified. Knowledge publication always requires human review and a separately authorized publish action.")
                .tools(callbacks)
                .hooks(new KnowledgeInputGuardHook(objectMapper), memoryHook)
                .interceptors(new com.bkanent.common.a2a.A2aAuthenticationToolInterceptor(),
                        new A2aSupervisorContextInterceptor())
                .outputKey("output")
                .build();
    }

    public ReactAgent knowledgeReactAgent() { return reactAgent; }

    public io.a2a.spec.AgentCard knowledgePublishedAgentCard(
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties server,
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties card) {
        return com.bkanent.common.a2a.SkillAgentCardPublisher.publish(
                new com.alibaba.cloud.ai.a2a.autoconfigure.server.A2aServerAgentCardAutoConfiguration()
                        .agentCard(reactAgent, server, card), skillRegistry, DOMAIN);
    }

    public AgentExecutor knowledgeA2aAgentExecutor(ObjectMapper objectMapper,
            com.bkanent.common.a2a.A2aExecutionProperties execution) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured(DOMAIN),
                skillRegistry, DOMAIN, executionTools, execution.getStreamTimeoutMs(), memoryHook);
    }
}
