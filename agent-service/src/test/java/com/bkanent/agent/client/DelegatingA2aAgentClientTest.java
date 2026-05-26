package com.bkanent.agent.client;

import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.registry.AgentDescriptorSource;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.common.agent.A2aAsyncTaskCreateResponse;
import com.bkanent.common.agent.A2aAsyncTaskStatusResponse;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelegatingA2aAgentClientTest {

    private HttpA2aAgentClient httpA2aAgentClient;
    private OfficialA2aAgentClient officialA2aAgentClient;
    private DelegatingA2aAgentClient delegatingA2aAgentClient;

    @BeforeEach
    void setUp() {
        httpA2aAgentClient = mock(HttpA2aAgentClient.class);
        officialA2aAgentClient = mock(OfficialA2aAgentClient.class);
        delegatingA2aAgentClient = new DelegatingA2aAgentClient(httpA2aAgentClient, officialA2aAgentClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "listing-agent",
            "marketing-agent",
            "media-agent",
            "trade-agent",
            "contract-agent",
            "settlement-agent",
            "notification-agent"
    })
    void shouldUseOfficialInvokeForMigratedAgents(String agentId) {
        RegisteredAgentDescriptor descriptor = descriptor(agentId, "/.well-known/agent.json");
        AgentTaskInvokeRequest request = request(agentId);
        AgentTaskInvokeResponse response = new AgentTaskInvokeResponse("s", "t", agentId, "COMPLETED", Map.of(), List.of(), List.of(), "ok", "trace");
        when(officialA2aAgentClient.invoke(eq(descriptor), eq(request))).thenReturn(response);

        AgentTaskInvokeResponse actual = delegatingA2aAgentClient.invoke(descriptor, request);

        assertThat(actual).isSameAs(response);
        verify(officialA2aAgentClient).invoke(eq(descriptor), eq(request));
        verify(httpA2aAgentClient, never()).invoke(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "listing-agent",
            "marketing-agent",
            "media-agent",
            "trade-agent",
            "contract-agent",
            "settlement-agent",
            "notification-agent"
    })
    void shouldUseOfficialAsyncForMigratedAgents(String agentId) {
        RegisteredAgentDescriptor descriptor = descriptor(agentId, "/.well-known/agent.json");
        AgentTaskInvokeRequest request = request(agentId);
        A2aAsyncTaskCreateResponse accepted = new A2aAsyncTaskCreateResponse("a1", "SUBMITTED", agentId, "s", "t", "trace");
        A2aAsyncTaskStatusResponse status = new A2aAsyncTaskStatusResponse("s", "t", agentId, "a1", "COMPLETED", null, null, null, "trace");
        when(officialA2aAgentClient.submitAsync(eq(descriptor), eq(request))).thenReturn(accepted);
        when(officialA2aAgentClient.queryAsyncStatus(eq(descriptor), eq("a1"))).thenReturn(status);

        A2aAsyncTaskCreateResponse actualAccepted = delegatingA2aAgentClient.submitAsync(descriptor, request);
        A2aAsyncTaskStatusResponse actualStatus = delegatingA2aAgentClient.queryAsyncStatus(descriptor, "a1");

        assertThat(actualAccepted).isSameAs(accepted);
        assertThat(actualStatus).isSameAs(status);
        verify(officialA2aAgentClient).submitAsync(eq(descriptor), eq(request));
        verify(officialA2aAgentClient).queryAsyncStatus(eq(descriptor), eq("a1"));
        verify(httpA2aAgentClient, never()).submitAsync(any(), any());
        verify(httpA2aAgentClient, never()).queryAsyncStatus(any(), any());
    }

    @Test
    void shouldFallbackToHttpForNonMigratedAgent() {
        RegisteredAgentDescriptor descriptor = descriptor("custom-agent", "/internal/agent-card");
        AgentTaskInvokeRequest request = request("custom-agent");
        AgentTaskInvokeResponse response = new AgentTaskInvokeResponse("s", "t", "custom-agent", "COMPLETED", Map.of(), List.of(), List.of(), "ok", "trace");
        when(httpA2aAgentClient.invoke(eq(descriptor), eq(request))).thenReturn(response);

        AgentTaskInvokeResponse actual = delegatingA2aAgentClient.invoke(descriptor, request);

        assertThat(actual).isSameAs(response);
        verify(httpA2aAgentClient).invoke(eq(descriptor), eq(request));
        verify(officialA2aAgentClient, never()).invoke(any(), any());
    }

    private RegisteredAgentDescriptor descriptor(String agentId, String cardPath) {
        AgentCard card = new AgentCard(
                agentId,
                agentId,
                "desc",
                "1.0.0",
                List.of(),
                List.of(agentId.replace("-agent", "")),
                true,
                true,
                "http://127.0.0.1/a2a",
                List.of("text"),
                List.of("text")
        );
        return new RegisteredAgentDescriptor(
                agentId,
                "http://127.0.0.1:9999",
                cardPath,
                "/a2a",
                "/a2a",
                "/a2a",
                "/a2a",
                "/.well-known/agent.json".equals(cardPath) ? AgentRuntimeType.ALIBABA_A2A : AgentRuntimeType.CUSTOM_HTTP,
                "listing-agent".equals(agentId) ? "plain" : "structured",
                "/.well-known/agent.json".equals(cardPath) ? AgentDescriptorSource.DISCOVERED_CARD : AgentDescriptorSource.STATIC_CONFIG,
                card
        );
    }

    private AgentTaskInvokeRequest request(String targetAgentId) {
        return new AgentTaskInvokeRequest(
                "session-1",
                "task-1",
                null,
                "trace-1",
                "supervisor-agent",
                targetAgentId,
                "test.intent",
                "test",
                "hello",
                Map.of("userId", "u1"),
                List.of(),
                List.of(),
                "text",
                "idem-1",
                false
        );
    }
}
