package com.bkanent.agent.service;

import com.bkanent.agent.client.A2aAgentClient;
import com.bkanent.agent.client.ChildAgentStreamEvent;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.registry.AgentDescriptorSource;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.A2aAsyncTaskCreateResponse;
import com.bkanent.common.agent.A2aAsyncTaskStatusResponse;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.SessionStreamEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
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
        ArgumentCaptor<SessionStreamEvent> events = ArgumentCaptor.forClass(SessionStreamEvent.class);
        verify(streamService, times(3)).publish(events.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) events.getAllValues().get(2).metadata().get("result");
        assertThat(result)
                .containsEntry("status", "COMPLETED")
                .containsEntry("summary", "final");
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
        ArgumentCaptor<SessionStreamEvent> events = ArgumentCaptor.forClass(SessionStreamEvent.class);
        verify(streamService, times(2)).publish(events.capture());
        assertThat(events.getAllValues().get(1).metadata()).containsKey("result");
    }

    @Test
    void publishesFailedTerminalEventWhenBlockingInvocationReturnsFailureResponse() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(false, false);
        AgentTaskInvokeRequest request = request(true);
        AgentTaskInvokeResponse response = new AgentTaskInvokeResponse(
                "session-1", "task-1", "listing-agent", "FAILED",
                Map.of("error", "child validation failed", "remoteTaskId", "remote-task-1"),
                List.of("remote-artifact-1"), List.of(), "child validation failed", "trace-1");
        when(client.invoke(eq(descriptor), eq(request))).thenReturn(response);

        A2aExecutionService service = new A2aExecutionService(
                client, streamService, permissionService, new com.bkanent.agent.config.DistributedAgentProperties());

        assertThat(service.execute(descriptor, request, "test", Map.of())).isSameAs(response);
        ArgumentCaptor<SessionStreamEvent> events = ArgumentCaptor.forClass(SessionStreamEvent.class);
        verify(streamService, times(2)).publish(events.capture());
        assertThat(events.getAllValues().get(1).eventType()).isEqualTo("agent.failed");
        assertThat(events.getAllValues().get(1).metadata()).containsKey("result");
    }

    @Test
    void boundsAsyncChildPollingAndPublishesDeadlineEvent() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(false, true);
        AgentTaskInvokeRequest request = request(true);
        when(client.submitAsync(eq(descriptor), eq(request))).thenReturn(new A2aAsyncTaskCreateResponse(
                "session-1", "task-1", "listing-agent", "remote-task-1", "WORKING", "trace-1"));
        when(client.queryAsyncStatus(eq(descriptor), eq("remote-task-1"))).thenReturn(
                new A2aAsyncTaskStatusResponse("session-1", "task-1", "listing-agent", "remote-task-1",
                        "WORKING", null, null, null, "trace-1"));
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getAsyncRuntime().setChildTaskTimeoutMs(10L);
        properties.getAsyncRuntime().setChildTaskInitialPollIntervalMs(1L);
        properties.getAsyncRuntime().setChildTaskMaxPollIntervalMs(1L);
        properties.getAsyncRuntime().setChildTaskPollJitterPercent(0);
        A2aExecutionService service = new A2aExecutionService(client, streamService, permissionService, properties);

        assertThatThrownBy(() -> service.execute(descriptor, request, "test", Map.of()))
                .isInstanceOf(A2aTaskDeadlineExceededException.class)
                .hasMessageContaining("exceeded its 10ms deadline");

        ArgumentCaptor<SessionStreamEvent> events = ArgumentCaptor.forClass(SessionStreamEvent.class);
        verify(streamService, org.mockito.Mockito.atLeastOnce()).publish(events.capture());
        assertThat(events.getAllValues()).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("a2a.async.timed_out");
            assertThat(event.metadata()).containsEntry("errorCode", "A2A_TASK_DEADLINE_EXCEEDED");
        });
        verify(client, timeout(1000)).cancelAsyncTask(descriptor, "remote-task-1");
    }

    @Test
    void pollsAsyncChildUntilCompletionAndReturnsStructuredResult() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(false, true);
        AgentTaskInvokeRequest request = request(true);
        AgentTaskInvokeResponse response = response("async final");
        when(client.submitAsync(eq(descriptor), eq(request))).thenReturn(new A2aAsyncTaskCreateResponse(
                "session-1", "task-1", "listing-agent", "remote-task-1", "WORKING", "trace-1"));
        when(client.queryAsyncStatus(eq(descriptor), eq("remote-task-1"))).thenReturn(
                new A2aAsyncTaskStatusResponse("session-1", "task-1", "listing-agent", "remote-task-1",
                        "WORKING", null, null, null, "trace-1"),
                new A2aAsyncTaskStatusResponse("session-1", "task-1", "listing-agent", "remote-task-1",
                        "COMPLETED", response, null, null, "trace-1"));
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getAsyncRuntime().setChildTaskTimeoutMs(1000L);
        properties.getAsyncRuntime().setChildTaskInitialPollIntervalMs(1L);
        properties.getAsyncRuntime().setChildTaskMaxPollIntervalMs(1L);
        properties.getAsyncRuntime().setChildTaskPollJitterPercent(0);
        A2aExecutionService service = new A2aExecutionService(client, streamService, permissionService, properties);

        assertThat(service.execute(descriptor, request, "test", Map.of())).isSameAs(response);

        verify(client, org.mockito.Mockito.times(2)).queryAsyncStatus(descriptor, "remote-task-1");
        ArgumentCaptor<SessionStreamEvent> events = ArgumentCaptor.forClass(SessionStreamEvent.class);
        verify(streamService, org.mockito.Mockito.atLeastOnce()).publish(events.capture());
        assertThat(events.getAllValues()).anySatisfy(event -> assertThat(event.eventType()).isEqualTo("a2a.async.completed"));
    }

    @Test
    void boundsIndividualStatusRequestsEvenWhenRemoteClientStalls() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(false, true);
        AgentTaskInvokeRequest request = request(true);
        when(client.submitAsync(eq(descriptor), eq(request))).thenReturn(new A2aAsyncTaskCreateResponse(
                "session-1", "task-1", "listing-agent", "remote-task-1", "WORKING", "trace-1"));
        when(client.queryAsyncStatus(eq(descriptor), eq("remote-task-1"))).thenAnswer(invocation -> {
            Thread.sleep(1000L);
            return new A2aAsyncTaskStatusResponse("session-1", "task-1", "listing-agent", "remote-task-1",
                    "WORKING", null, null, null, "trace-1");
        });
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getAsyncRuntime().setChildTaskTimeoutMs(50L);
        properties.getAsyncRuntime().setChildTaskPollRequestTimeoutMs(5L);
        properties.getAsyncRuntime().setChildTaskInitialPollIntervalMs(1L);
        properties.getAsyncRuntime().setChildTaskMaxPollIntervalMs(1L);
        properties.getAsyncRuntime().setChildTaskPollJitterPercent(0);
        A2aExecutionService service = new A2aExecutionService(client, streamService, permissionService, properties);

        assertThatThrownBy(() -> service.execute(descriptor, request, "test", Map.of()))
                .isInstanceOf(A2aTaskDeadlineExceededException.class);

        verify(client, org.mockito.Mockito.atLeastOnce()).queryAsyncStatus(descriptor, "remote-task-1");
    }

    @Test
    void boundsStatusQueriesFromThePersistedChildTaskStatusEndpoint() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(false, true);
        when(client.queryAsyncStatus(eq(descriptor), eq("remote-task-1"))).thenAnswer(invocation -> {
            Thread.sleep(1_000L);
            return new A2aAsyncTaskStatusResponse("session-1", "task-1", "listing-agent", "remote-task-1",
                    "WORKING", null, null, null, "trace-1");
        });
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getAsyncRuntime().setChildTaskPollRequestTimeoutMs(10L);
        A2aExecutionService service = new A2aExecutionService(client, streamService, permissionService, properties);

        assertThatThrownBy(() -> service.queryChildAsyncTaskStatus(descriptor, "remote-task-1"))
                .isInstanceOf(A2aStatusQueryTimeoutException.class)
                .hasMessageContaining("A2A status query");
    }

    @Test
    void doesNotBlindlyRetryWhenAsyncSubmissionAcceptanceIsUnknown() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(false, true);
        AgentTaskInvokeRequest request = request(true);
        when(client.submitAsync(eq(descriptor), eq(request))).thenAnswer(invocation -> {
            Thread.sleep(1000L);
            return new A2aAsyncTaskCreateResponse(
                    "session-1", "task-1", "listing-agent", "remote-task-1", "WORKING", "trace-1");
        });
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getAsyncRuntime().setChildTaskSubmitRequestTimeoutMs(5L);
        A2aExecutionService service = new A2aExecutionService(client, streamService, permissionService, properties);

        assertThatThrownBy(() -> service.execute(descriptor, request, "test", Map.of()))
                .isInstanceOf(A2aTaskSubmissionOutcomeUnknownException.class);

        verify(client, times(0)).queryAsyncStatus(any(), any());
    }

    @Test
    void boundsSynchronousChildInvocationByTheConfiguredChildTaskDeadline() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(false, false);
        AgentTaskInvokeRequest request = request(false);
        when(client.invoke(eq(descriptor), eq(request))).thenAnswer(invocation -> {
            Thread.sleep(1_000L);
            return response("too late");
        });
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getAsyncRuntime().setChildTaskTimeoutMs(25L);
        A2aExecutionService service = new A2aExecutionService(client, streamService, permissionService, properties);

        assertThatThrownBy(() -> service.execute(descriptor, request, "test", Map.of()))
                .isInstanceOf(A2aTaskDeadlineExceededException.class)
                .hasMessageContaining("A2A child invocation");

        verify(client, timeout(500)).invoke(descriptor, request);
    }

    @Test
    void boundsSynchronousInvocationUsedWhenRegeneratingAnApprovedWorkflow() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(false, false);
        AgentTaskInvokeRequest request = request(false);
        when(client.invoke(eq(descriptor), eq(request))).thenAnswer(invocation -> {
            Thread.sleep(1_000L);
            return response("too late");
        });
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getAsyncRuntime().setChildTaskTimeoutMs(25L);
        A2aExecutionService service = new A2aExecutionService(client, streamService, permissionService, properties);

        assertThatThrownBy(() -> service.invokeChildSynchronously(descriptor, request))
                .isInstanceOf(A2aTaskDeadlineExceededException.class)
                .hasMessageContaining("A2A child invocation");

        verify(permissionService).assertCanInvokeChildAgent(descriptor, request);
        verify(client, timeout(500)).invoke(descriptor, request);
    }

    @Test
    void boundsStreamingChildAndBestEffortCancelsTheObservedRemoteTask() {
        A2aAgentClient client = mock(A2aAgentClient.class);
        SessionStreamService streamService = mock(SessionStreamService.class);
        AgentPermissionService permissionService = mock(AgentPermissionService.class);
        RegisteredAgentDescriptor descriptor = descriptor(true, false);
        AgentTaskInvokeRequest request = request(true);
        when(client.supportsStreaming(eq(descriptor), eq(request))).thenReturn(true);
        when(client.stream(eq(descriptor), eq(request), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<ChildAgentStreamEvent> consumer = invocation.getArgument(2);
            consumer.accept(new ChildAgentStreamEvent(
                    "agent.progress", "working", Map.of("childTaskId", "remote-stream-task"), false, null));
            Thread.sleep(1_000L);
            return response("too late");
        });
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getAsyncRuntime().setChildTaskTimeoutMs(25L);
        A2aExecutionService service = new A2aExecutionService(client, streamService, permissionService, properties);

        assertThatThrownBy(() -> service.execute(descriptor, request, "test", Map.of()))
                .isInstanceOf(A2aTaskDeadlineExceededException.class)
                .hasMessageContaining("A2A child stream");

        verify(client, timeout(1000)).cancelAsyncTask(descriptor, "remote-stream-task");
    }

    @Test
    void childTimeoutDefaultsToThirtyMinutesAndLeaseOwnersAreUniquePerRuntimeInstance() {
        DistributedAgentProperties first = new DistributedAgentProperties();
        DistributedAgentProperties second = new DistributedAgentProperties();

        assertThat(first.getAsyncRuntime().getChildTaskTimeoutMs()).isEqualTo(1_800_000L);
        assertThat(first.getAsyncRuntime().getInstanceWorkerId())
                .startsWith(first.getAsyncRuntime().getWorkerId())
                .isNotEqualTo(second.getAsyncRuntime().getInstanceWorkerId());
        assertThat(first.getAsyncRuntime().createLeaseOwner())
                .isNotEqualTo(first.getAsyncRuntime().createLeaseOwner());
        first.getAsyncRuntime().setWorkerId("worker-".repeat(100));
        assertThat(first.getAsyncRuntime().createLeaseOwner()).hasSizeLessThanOrEqualTo(128);
    }

    private RegisteredAgentDescriptor descriptor(boolean streaming, boolean async) {
        AgentCard card = new AgentCard(
                "listing-agent", "listing-agent", "desc", "1.0.0", List.of(), List.of("listing"),
                streaming, async, "http://127.0.0.1/a2a", List.of("text"), List.of("text"));
        return new RegisteredAgentDescriptor(
                "listing-agent", "http://127.0.0.1:9999", "/.well-known/agent.json", "/a2a",
                AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD, card, java.util.Map.of());
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
