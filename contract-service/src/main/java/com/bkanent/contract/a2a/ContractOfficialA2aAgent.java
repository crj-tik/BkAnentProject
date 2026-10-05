package com.bkanent.contract.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.SkillRoutingModelInterceptor;
import com.bkanent.common.skill.runtime.SkillTool;
import com.bkanent.contract.config.ContractAgentProperties;
import com.bkanent.contract.tool.ContractTools;
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
public class ContractOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";
    private static final String DOMAIN = "contract";

    private final ReactAgent reactAgent;
    private final SkillRegistry skillRegistry;
    private List<ToolCallback> executionTools;

    public ContractOfficialA2aAgent(ChatModel chatModel,
                                    ContractAgentProperties properties,
                                    ContractTools contractTools,
                                    SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(
                MethodToolCallbackProvider.builder().toolObjects(contractTools).build().getToolCallbacks()));
        tools.add(new SkillTool(skillRegistry, DOMAIN));
        executionTools = List.copyOf(tools);

        this.reactAgent = ReactAgent.builder()
                .name("contract-agent")
                .description("Responsible for contract parsing, risk review, and contract lifecycle management with LLM-driven analysis")
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
    public ReactAgent contractReactAgent() {
        return reactAgent;
    }

    @Bean
    public io.a2a.spec.AgentCard contractPublishedAgentCard(
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties server,
            com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties card) {
        return com.bkanent.common.a2a.SkillAgentCardPublisher.publish(
                new com.alibaba.cloud.ai.a2a.autoconfigure.server.A2aServerAgentCardAutoConfiguration()
                        .agentCard(reactAgent, server, card), skillRegistry, "contract");
    }

    @Bean
    public AgentExecutor contractA2aAgentExecutor(ObjectMapper objectMapper, com.bkanent.common.a2a.A2aExecutionProperties execution) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("contract"),
                skillRegistry, DOMAIN, executionTools, execution.getStreamTimeoutMs());
    }
}
