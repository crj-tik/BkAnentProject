package com.bkanent.settlement.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.SkillRoutingModelInterceptor;
import com.bkanent.common.skill.runtime.SkillTool;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.settlement.config.SettlementAgentProperties;
import com.bkanent.settlement.tool.SettlementTools;
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
public class SettlementOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";
    private static final String DOMAIN = "settlement";

    private final ReactAgent reactAgent;

    public SettlementOfficialA2aAgent(ChatModel chatModel,
                                      SettlementAgentProperties properties,
                                      SettlementTools settlementTools,
                                      SkillRegistry skillRegistry) {
        this.reactAgent = buildAgent(chatModel, properties, settlementTools, skillRegistry);
    }

    private ReactAgent buildAgent(ChatModel chatModel, SettlementAgentProperties properties,
                                  SettlementTools settlementTools, SkillRegistry skillRegistry) {
        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(MethodToolCallbackProvider.builder().toolObjects(settlementTools).build().getToolCallbacks()));
        tools.add(new SkillTool(skillRegistry, DOMAIN));
        return ReactAgent.builder()
                .name("settlement-agent")
                .description("Responsible for settlement calculation, commission computation, payout batch preparation, and monthly summary analysis with LLM-driven reasoning")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(tools)
                .interceptors(new A2aSupervisorContextInterceptor(),
                        new SkillRoutingModelInterceptor(skillRegistry, DOMAIN))
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent settlementReactAgent() {
        return reactAgent;
    }

    @Bean
    public AgentExecutor settlementA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("settlement"));
    }
}
