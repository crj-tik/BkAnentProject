package com.bkanent.listing.a2a;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.rag.A2aBeaconBinding;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
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
    private static final String AUDIENCE = "listing-master-service";

    @Test
    void actualServicePublishesAndExecutesExplicitAndDefaultContracts() throws Exception {
        var registry = new SkillRegistry(new SkillFileLoader(), "");
        var selected = registry.findOperationalSkills("listing").stream().filter(skill -> !skill.tools().isEmpty()).findFirst().orElseThrow();
        ChatModel model = mock(ChatModel.class); AtomicInteger turns = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            for (var message : prompt.getInstructions()) {
                assertThat(new ObjectMapper().writeValueAsString(message.getMetadata()))
                        .doesNotContain("opaque-wiring-test-", "accessToken", "beacon", "A2aLease", "RagCredentialContext");
                assertThat(message.getText()).doesNotContain("opaque-wiring-test-");
            }
            if (turns.getAndIncrement() == 0) assertThat(prompt.getSystemMessage().getText()).contains(selected.systemPrompt(), "验收原始需求");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"decision\":\"SUCCESS\",\"summary\":\"完成\"}"))));
        });
        var constructor = ListingOfficialA2aAgent.class.getConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        var types = constructor.getParameterTypes();
        args[0] = model; args[1] = types[1].getConstructor().newInstance(); args[3] = registry;
        args[2] = ToolCallbackProvider.class.isAssignableFrom(types[2])
                ? MethodToolCallbackProvider.builder().toolObjects(mock(Class.forName("com.bkanent.listing.tool.ListingTools"))).build()
                : mock(types[2]);
        Object component = constructor.newInstance(args);
        var card = (AgentCard) ListingOfficialA2aAgent.class.getMethod("listingPublishedAgentCard",
                com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties.class, com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties.class)
                .invoke(component, new com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties(), new com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties());
        assertThat(card.capabilities().extensions()).anySatisfy(extension -> assertThat(extension.uri()).isEqualTo(SkillPublication.EXTENSION_URI));
        assertThat(card.skills()).anySatisfy(skill -> assertThat(skill.name()).isEqualTo(selected.name()));
        var executor = (AgentExecutor) ListingOfficialA2aAgent.class.getMethod("listingA2aAgentExecutor", ObjectMapper.class, com.bkanent.common.a2a.A2aExecutionProperties.class)
                .invoke(component, new ObjectMapper(), new com.bkanent.common.a2a.A2aExecutionProperties());
        var selection = Map.of("name", selected.name(), "owner", "listing", "version", selected.version(), "contentHash", selected.contentHash(), "mode", "explicit");
        assertThat(A2aCredentialContext.snapshot()).isNull();
        try (QueueScope unauthenticated = new QueueScope()) {
            assertThatThrownBy(() -> executor.execute(context("no-lease", Map.of("supervisor", Map.of("skillSelection", selection))), unauthenticated.queue))
                    .isInstanceOfSatisfying(RagException.class,
                            exception -> assertThat(exception.code()).isEqualTo(RagErrorCode.AUTHENTICATION_FAILED));
            assertThat(unauthenticated.queue.dequeueEvent(0)).isNull();
        }
        assertThat(turns).hasValue(0);
        verify(model, never()).call(any(Prompt.class));
        try (QueueScope explicit = new QueueScope()) {
            executeAuthenticated(executor, context("explicit", Map.of("supervisor", Map.of("skillSelection", selection))), explicit.queue);
            assertCompleted(explicit.queue);
        }
        assertThat(turns).hasValue(1);
        try (QueueScope ordinary = new QueueScope()) {
            executeAuthenticated(executor, context("ordinary", Map.of("supervisor", Map.of("parentSkill", Map.of("name", "parent-only", "owner", "supervisor")))), ordinary.queue);
            assertCompleted(ordinary.queue);
        }
        assertThat(turns).hasValue(2);
        Map<String,Object> bad = new LinkedHashMap<>(selection); bad.put("version", "unknown-version");
        try (QueueScope mismatch = new QueueScope()) {
            executeAuthenticated(executor, context("bad", Map.of("supervisor", Map.of("skillSelection", bad))), mismatch.queue);
            boolean found = false; Event event;
            while ((event = mismatch.queue.dequeueEvent(0)) != null) if (event instanceof TaskStatusUpdateEvent update) {
                assertThat(update.getStatus().state()).isEqualTo(TaskState.FAILED);
                found |= update.getStatus().message() != null && "SKILL_VERSION_MISMATCH".equals(update.getStatus().message().getMetadata().get("errorCode"));
            }
            assertThat(found).isTrue();
        }
        assertThat(turns).hasValue(2);
    }
    private void executeAuthenticated(AgentExecutor executor, RequestContext context, EventQueue queue) throws Exception {
        long expiresAt = java.time.Instant.now().getEpochSecond() + 60;
        var binding = new A2aBeaconBinding(1, context.getTaskId() + "-request", "agent-service", AUDIENCE,
                "7", "message/send", "0".repeat(64), context.getTaskId(), expiresAt);
        var credentials = RagCredentialContext.beacon("opaque-wiring-test-" + context.getTaskId());
        AtomicInteger validations = new AtomicInteger();
        var lease = new A2aCredentialContext.Lease(binding, credentials, expiresAt, () -> {
            assertThat(binding.taskId()).isEqualTo(context.getTaskId());
            assertThat(binding.audience()).isEqualTo(AUDIENCE);
            assertThat(binding.actorId()).isEqualTo("7");
            validations.incrementAndGet();
        });
        var previous = A2aCredentialContext.snapshot();
        try (var ignored = A2aCredentialContext.open(lease)) {
            executor.execute(context, queue);
        }
        assertThat(A2aCredentialContext.snapshot()).isSameAs(previous);
        assertThat(validations.get()).isGreaterThanOrEqualTo(2);
        assertThat(new ObjectMapper().writeValueAsString(context.getParams().metadata()))
                .doesNotContain(credentials.beacon(), "accessToken", "beacon", "A2aLease", "RagCredentialContext");
    }

    private RequestContext context(String id, Map<String,Object> metadata) {
        id = AUDIENCE + "-" + id;
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



