package com.bkanent.notification.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.a2a.A2aOutputPolicy;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.notification.config.NotificationAgentProperties;
import com.bkanent.notification.tool.NotificationTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class NotificationOfficialA2aAgent {

    private static final String OUTPUT_KEY = "output";

    private final ReactAgent reactAgent;

    public NotificationOfficialA2aAgent(ChatModel chatModel,
                                        NotificationAgentProperties properties,
                                        NotificationTools notificationTools) {
        this.reactAgent = ReactAgent.builder()
                .name("notification-agent")
                .description("Responsible for in-app station messages, email notifications, and message management with LLM-driven routing")
                .model(chatModel)
                .systemPrompt(properties.getSystemPrompt())
                .tools(MethodToolCallbackProvider.builder().toolObjects(notificationTools).build().getToolCallbacks())
                .interceptors(new A2aSupervisorContextInterceptor())
                .outputKey(OUTPUT_KEY)
                .build();
    }

    @Bean
    public ReactAgent notificationReactAgent() {
        return reactAgent;
    }

    @Bean
    public AgentExecutor notificationA2aAgentExecutor(ObjectMapper objectMapper) {
        return new OfficialA2aAgentExecutor(reactAgent, objectMapper, A2aOutputPolicy.structured("notification"));
    }
}
