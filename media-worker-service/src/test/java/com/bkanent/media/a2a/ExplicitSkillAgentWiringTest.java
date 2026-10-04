package com.bkanent.media.a2a;

import com.bkanent.common.skill.core.SkillFileLoader;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.agent.SkillPublication;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExplicitSkillAgentWiringTest {
    @Test
    void actualServicePublishesAndExecutesExplicitAndDefaultContracts() throws Exception {
        var registry = new SkillRegistry(new SkillFileLoader(), "");
        var selected = registry.findOperationalSkills("media").stream().filter(skill -> !skill.tools().isEmpty()).findFirst().orElseThrow();
        ChatModel model = mock(ChatModel.class); AtomicInteger turns = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            if (turns.getAndIncrement() == 0) assertThat(prompt.getSystemMessage().getText()).contains(selected.systemPrompt(), "验收原始需求");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"decision\":\"SUCCESS\",\"summary\":\"完成\"}"))));
        });
        var constructor = MediaOfficialA2aAgent.class.getConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        var types = constructor.getParameterTypes();
        args[0] = model; args[1] = types[1].getConstructor().newInstance(); args[3] = registry;
        args[2] = ToolCallbackProvider.class.isAssignableFrom(types[2])
                ? MethodToolCallbackProvider.builder().toolObjects(mock(Class.forName("com.bkanent.listing.tool.ListingTools"))).build()
                : mock(types[2]);
        Object component = constructor.newInstance(args);
        var card = (AgentCard) MediaOfficialA2aAgent.class.getMethod("mediaPublishedAgentCard",
                com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties.class, com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties.class)
                .invoke(component, new com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties(), new com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties());
        assertThat(card.capabilities().extensions()).anySatisfy(extension -> assertThat(extension.uri()).isEqualTo(SkillPublication.EXTENSION_URI));
        assertThat(card.skills()).anySatisfy(skill -> assertThat(skill.name()).isEqualTo(selected.name()));
        var executor = (AgentExecutor) MediaOfficialA2aAgent.class.getMethod("mediaA2aAgentExecutor", ObjectMapper.class).invoke(component, new ObjectMapper());
        var selection = Map.of("name", selected.name(), "owner", "media", "version", selected.version(), "contentHash", selected.contentHash(), "mode", "explicit");
        try (QueueScope explicit = new QueueScope()) {
            executor.execute(context("explicit", Map.of("supervisor", Map.of("skillSelection", selection))), explicit.queue);
            assertCompleted(explicit.queue);
        }
        try (QueueScope ordinary = new QueueScope()) {
            executor.execute(context("ordinary", Map.of("supervisor", Map.of("parentSkill", Map.of("name", "parent-only", "owner", "supervisor")))), ordinary.queue);
            assertCompleted(ordinary.queue);
        }
        assertThat(turns).hasValue(2);
        Map<String,Object> bad = new LinkedHashMap<>(selection); bad.put("version", "unknown-version");
        try (QueueScope mismatch = new QueueScope()) {
            executor.execute(context("bad", Map.of("supervisor", Map.of("skillSelection", bad))), mismatch.queue);
            boolean found = false; Event event;
            while ((event = mismatch.queue.dequeueEvent(0)) != null) if (event instanceof TaskStatusUpdateEvent update)
                found |= update.getStatus().message() != null && "SKILL_VERSION_MISMATCH".equals(update.getStatus().message().getMetadata().get("errorCode"));
            assertThat(found).isTrue();
        }
        assertThat(turns).hasValue(2);
    }
    private RequestContext context(String id, Map<String,Object> metadata) {
        var message = new Message.Builder().role(Message.Role.USER).parts(List.of(new TextPart("验收原始需求"))).taskId(id).contextId(id).build();
        return new RequestContext.Builder().setParams(new MessageSendParams(message,
                new MessageSendConfiguration(List.of("application/json"), null, null, true), metadata)).setTaskId(id).setContextId(id).build();
    }
    private void assertCompleted(EventQueue queue) throws Exception {
        boolean found = false; Event event;
        while ((event = queue.dequeueEvent(0)) != null) if (event instanceof TaskStatusUpdateEvent update) found |= update.getStatus().state() == TaskState.COMPLETED;
        assertThat(found).isTrue();
    }
    private static class QueueScope implements AutoCloseable {
        final EventQueue queue = EventQueue.create();
        public void close() { queue.close(); }
    }
}



