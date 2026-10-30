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
    void unifiedCardRefreshesEveryInternalSkillOwner() throws Exception {
        SkillFileLoader loader = mock(SkillFileLoader.class);
        var trade = SkillDefinition.builder().name("analytics-report").description("analytics").domain("trade").build();
        var review = SkillDefinition.builder().name("review-draft").description("review").domain("business-review").build();
        when(loader.loadAll()).thenReturn(List.of(trade, review));
        SkillRegistry registry = new SkillRegistry(loader, "");
        AgentCard initial = SkillAgentCardPublisher.publish(base(), registry, "platform-operations",
                List.of("trade", "business-review", "knowledge"));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("card", initial);
        LiveSkillAgentCards live = new LiveSkillAgentCards(registry, beans.getBeanProvider(AgentCard.class),
                beans.getBeanProvider(NacosA2aOperationService.class));
        live.afterSingletonsInstantiated();
        try {
            assertThat(live.currentCard(initial).skills()).extracting(AgentSkill::id)
                    .contains("bk-skill/trade/analytics-report", "bk-skill/business-review/review-draft");
            when(loader.loadAll()).thenReturn(List.of(review));
            registry.reload();
            assertThat(live.currentCard(initial).skills()).extracting(AgentSkill::id)
                    .doesNotContain("bk-skill/trade/analytics-report").contains("bk-skill/business-review/review-draft");
        } finally { live.destroy(); }
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

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> errorResponseIds() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("\"request-id\"", "\"request-id\""),
                org.junit.jupiter.params.provider.Arguments.of("\"\"", "\"\""),
                org.junit.jupiter.params.provider.Arguments.of("42", "42"),
                org.junit.jupiter.params.provider.Arguments.of("-1", "-1"),
                org.junit.jupiter.params.provider.Arguments.of("2147483647", "2147483647"),
                org.junit.jupiter.params.provider.Arguments.of("-2147483648", "-2147483648"),
                org.junit.jupiter.params.provider.Arguments.of("null", "null"),
                org.junit.jupiter.params.provider.Arguments.of(null, "null"),
                org.junit.jupiter.params.provider.Arguments.of("2147483648", "null"),
                org.junit.jupiter.params.provider.Arguments.of("-2147483649", "null"),
                org.junit.jupiter.params.provider.Arguments.of("1.5", "null"),
                org.junit.jupiter.params.provider.Arguments.of("true", "null"),
                org.junit.jupiter.params.provider.Arguments.of("{}", "null"),
                org.junit.jupiter.params.provider.Arguments.of("[]", "null"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("errorResponseIds")
    void authenticationFailurePreservesOnlyOfficialScalarIdsThroughHttp(String inputId, String expectedId) {
        var jsonRpc = mock(io.a2a.server.requesthandlers.JSONRPCHandler.class);
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"message/send\",\"params\":{}"
                + (inputId == null ? "" : ",\"id\":" + inputId) + "}";
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(LiveSkillAgentCardAutoConfiguration.class))
                .withBean(JsonRpcA2aRequestHandler.class, () -> new JsonRpcA2aRequestHandler(jsonRpc))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var handler = context.getBean(JsonRpcA2aRequestHandler.class);
                    var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.routerFunctions(
                            new com.alibaba.cloud.ai.a2a.core.route.JsonRpcA2aRouterProvider().getRouter(handler)).build();
                    try {
                        var response = handler.onHandler(body, mock(org.springframework.web.servlet.function.ServerRequest.Headers.class));
                        assertThat(response).isInstanceOf(io.a2a.spec.JSONRPCErrorResponse.class);
                        var mapper = io.a2a.util.Utils.OBJECT_MAPPER;
                        var serialized = mapper.readTree(mapper.writeValueAsString(response));
                        if ("null".equals(expectedId)) assertThat(serialized.has("id")).isFalse();
                        else assertThat(serialized.path("id")).isEqualTo(mapper.readTree(expectedId));
                        assertThat(serialized.path("error").path("code").intValue()).isEqualTo(-32001);
                        var result = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/a2a")
                                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                        .contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.jsonrpc").value("2.0"))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value(-32001))
                                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.data.errorCode")
                                        .value("AUTHENTICATION_FAILED"))
                                .andReturn();
                        var httpResponse = mapper.readTree(result.getResponse().getContentAsString());
                        if ("null".equals(expectedId)) assertThat(httpResponse.has("id")).isFalse();
                        else assertThat(httpResponse.path("id")).isEqualTo(mapper.readTree(expectedId));
                        verifyNoInteractions(jsonRpc);
                    } catch (Exception exception) { throw new AssertionError(exception); }
                });
    }

    @Test
    void autoConfigurationServesLiveCardAndDelegatesRpcWithoutEnablingNacos() {
        assertThat(LiveSkillAgentCardAutoConfiguration.class
                .getAnnotation(org.springframework.boot.autoconfigure.AutoConfiguration.class).before())
                .contains(com.alibaba.cloud.ai.a2a.autoconfigure.server.A2aServerHandlerAutoConfiguration.class);
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
                    // Public Card remains unchanged; task RPC without dedicated auth is rejected
                    // with an official JSON-RPC error, before the SDK task handler is called.
                    assertThat(handler.onHandler("invalid", null)).isInstanceOf(io.a2a.spec.JSONRPCErrorResponse.class);
                    verify(jsonRpc, never()).onMessageSend(any());
                });
    }
}
