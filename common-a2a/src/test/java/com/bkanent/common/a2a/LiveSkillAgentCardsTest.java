package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.a2a.core.server.JsonRpcA2aRequestHandler;
import com.alibaba.cloud.ai.a2a.registry.nacos.service.NacosA2aOperationService;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillFileLoader;
import com.bkanent.common.skill.core.SkillRegistry;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentSkill;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveSkillAgentCardsTest {
    private SkillDefinition skill(String name, String body) {
        return SkillDefinition.builder().name(name).description(name).domain("listing").systemPrompt(body).build();
    }
    private AgentCard base() {
        return new AgentCard("listing-agent", "listing", "http://localhost/a2a", null, "1", null,
                new AgentCapabilities(true, false, true, List.of()), List.of("text"), List.of("text"),
                List.of(new AgentSkill("unrelated", "unrelated", "unrelated", List.of(), List.of(), List.of(), List.of())),
                false, Map.of(), List.of(), null, List.of(), "JSONRPC", "0.2.5");
    }

    @Test
    void reloadUpdatesHttpAndRetriesOnlyLatestNacosCardIncludingRemoval() throws Exception {
        SkillFileLoader loader = mock(SkillFileLoader.class);
        when(loader.loadAll()).thenReturn(List.of(skill("find", "old")));
        SkillRegistry registry = new SkillRegistry(loader, "");
        AgentCard initial = SkillAgentCardPublisher.publish(base(), registry, "listing");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("card", initial);
        var registration = mock(NacosA2aOperationService.class);
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(call -> { if (fail.get()) throw new IllegalStateException("offline"); return null; }).when(registration).registerAgent(any());
        beans.registerSingleton("nacos", registration);
        LiveSkillAgentCards live = new LiveSkillAgentCards(registry, beans.getBeanProvider(AgentCard.class),
                beans.getBeanProvider(NacosA2aOperationService.class));
        live.afterSingletonsInstantiated();
        try {
            when(loader.loadAll()).thenReturn(List.of(skill("find", "changed"), skill("new", "new")));
            registry.reload();
            AgentCard changed = live.currentCard(initial);
            assertThat(changed.skills()).extracting(AgentSkill::id).containsExactly("unrelated", "bk-skill/listing/find", "bk-skill/listing/new");
            assertThat(changed.capabilities().extensions()).isNotEqualTo(initial.capabilities().extensions());
            verify(registration, timeout(2000).atLeastOnce()).registerAgent(changed);
            when(loader.loadAll()).thenReturn(List.of());
            registry.reload();
            AgentCard removed = live.currentCard(initial);
            assertThat(removed.skills()).extracting(AgentSkill::id).containsExactly("unrelated");
            fail.set(false);
            live.publishPending();
            verify(registration, atLeastOnce()).registerAgent(removed);
            clearInvocations(registration);
            live.publishPending();
            verifyNoInteractions(registration);
        } finally { live.destroy(); }
    }

    @Test
    void autoConfigurationServesLiveCardAndDelegatesRpcWithoutEnablingNacos() {
        SkillFileLoader loader = mock(SkillFileLoader.class);
        when(loader.loadAll()).thenReturn(List.of(skill("find", "old")));
        SkillRegistry registry = new SkillRegistry(loader, "");
        AgentCard initial = SkillAgentCardPublisher.publish(base(), registry, "listing");
        // Use the exact Starter class that the post processor decorates.
        var jsonRpc = mock(io.a2a.server.requesthandlers.JSONRPCHandler.class);
        when(jsonRpc.getAgentCard()).thenReturn(initial);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(LiveSkillAgentCardAutoConfiguration.class))
                .withBean(SkillRegistry.class, () -> registry).withBean(AgentCard.class, () -> initial)
                .withBean(JsonRpcA2aRequestHandler.class, () -> new JsonRpcA2aRequestHandler(jsonRpc))
                .withPropertyValues("agent.a2a.execution.stream-timeout-ms=2500")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(NacosA2aOperationService.class);
                    var handler = context.getBean(JsonRpcA2aRequestHandler.class);
                    assertThat(handler.getAgentCard()).isEqualTo(initial);
                    var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.routerFunctions(
                            new com.alibaba.cloud.ai.a2a.core.route.JsonRpcA2aRouterProvider().getRouter(handler)).build();
                    when(loader.loadAll()).thenReturn(List.of(skill("other", "new")));
                    registry.reload();
                    assertThat(handler.getAgentCard().skills()).extracting(AgentSkill::name).containsExactly("unrelated", "other");
                    try {
                        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/.well-known/agent.json"))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.skills[1].name").value("other"));
                    } catch (Exception exception) { throw new AssertionError(exception); }
                    assertThat(context.getBean(A2aExecutionProperties.class).getStreamTimeoutMs()).isEqualTo(2500);
                    // Invalid JSON must still pass through the Starter's original RPC parsing.
                    assertThat(handler.onHandler("invalid", null)).isNotNull();
                });
    }
}
