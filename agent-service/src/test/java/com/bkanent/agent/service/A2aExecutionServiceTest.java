package com.bkanent.agent.service;

import com.bkanent.agent.client.A2aAgentClient;
import com.bkanent.agent.client.ChildAgentStreamEvent;
import com.bkanent.agent.registry.AgentDescriptorSource;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aExecutionServiceTest {

    @Test
    void streamsChildDeltasAndReturnsTerminalStructuredResponse() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(true, false);
        AgentTaskInvokeRequest request = request(true);
        AgentTaskInvokeResponse response = response("final");
        when(client.supportsStreaming(eq(descriptor), eq(request))).thenReturn(true);
        when(client.stream(eq(descriptor), eq(request), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<ChildAgentStreamEvent> consumer = invocation.getArgument(2);
            consumer.accept(new ChildAgentStreamEvent("agent.delta", "hel", Map.of("source", "test"), false, null));
            consumer.accept(new ChildAgentStreamEvent("agent.completed", "done", Map.of(), true, response));
            return response;
        });

        A2aExecutionService service = new A2aExecutionService(
                client, streamService, permissionService, new com.bkanent.agent.config.DistributedAgentProperties());

        AgentTaskInvokeResponse actual = service.execute(descriptor, request, "test", Map.of());

        assertThat(actual).isSameAs(response);
        verify(client).stream(eq(descriptor), eq(request), any());
        verify(client, times(0)).invoke(any(), any());
        verify(streamService, times(3)).publish(any());
    }

    @Test
    void fallsBackToBlockingInvocationWhenStreamingClientCannotStream() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(true, false);
        AgentTaskInvokeRequest request = request(true);
        AgentTaskInvokeResponse response = response("blocking");
        when(client.supportsStreaming(eq(descriptor), eq(request))).thenReturn(true);
        when(client.stream(eq(descriptor), eq(request), any()))
                .thenThrow(new UnsupportedOperationException("not supported"));
        when(client.invoke(eq(descriptor), eq(request))).thenReturn(response);

        A2aExecutionService service = new A2aExecutionService(
                client, streamService, permissionService, new com.bkanent.agent.config.DistributedAgentProperties());

        assertThat(service.execute(descriptor, request, "test", Map.of())).isSameAs(response);
        verify(client).invoke(eq(descriptor), eq(request));
        verify(streamService, times(2)).publish(any());
    }

    private RegisteredAgentDescriptor descriptor(boolean streaming, boolean async) {
        AgentCard card = new AgentCard(
                "listing-agent", "listing-agent", "desc", "1.0.0", List.of(), List.of("listing"),
                streaming, async, "http://127.0.0.1/a2a", List.of("text"), List.of("text"));
        return new RegisteredAgentDescriptor(
                "listing-agent", "http://127.0.0.1:9999", "/.well-known/agent.json", "/a2a", "/a2a",
                "/a2a", "/a2a", AgentRuntimeType.ALIBABA_A2A, "plain",
                AgentDescriptorSource.DISCOVERED_CARD, card);
    }

    private AgentTaskInvokeRequest request(boolean stream) {
        return new AgentTaskInvokeRequest(
                "session-1", "task-1", "parent-1", "trace-1", "supervisor-agent", "listing-agent",
                "listing.search", "listing", "hello", Map.of("childRunId", "child-1"), List.of(), List.of(),
                "text", "idem-1", stream);
    }

    private AgentTaskInvokeResponse response(String summary) {
        return new AgentTaskInvokeResponse(
                "session-1", "task-1", "listing-agent", "COMPLETED", Map.of("output", summary),
                List.of(), List.of(), summary, "trace-1");
    }
}
