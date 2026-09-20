package com.bkanent.agent.client;

import com.bkanent.agent.registry.AgentDescriptorSource;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.client.A2AClient;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.GetTaskResponse;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficialA2aAgentClientTest {

    @Test
    void syncStreamingAndAsyncResultsUseTheSameNormalizedContract() throws Exception {
        A2AClient a2aClient = mock(A2AClient.class);
        OfficialA2aAgentClient client = new OfficialA2aAgentClient(
                new OfficialA2aResponseNormalizer(new ObjectMapper()), ignored -> a2aClient);
        RegisteredAgentDescriptor descriptor = descriptor();
        AgentTaskInvokeRequest request = request();
        Artifact artifact = artifact();
        Task task = new Task(
                "remote-task-1", "context-1", new TaskStatus(TaskState.COMPLETED),
                List.of(artifact), List.of(), Map.of());
        when(a2aClient.sendMessage(any())).thenReturn(
                new SendMessageResponse(null, task),
                new SendMessageResponse(null, task));
        when(a2aClient.getTask("remote-task-1")).thenReturn(new GetTaskResponse(null, task));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<io.a2a.spec.StreamingEventKind> consumer = invocation.getArgument(1);
            consumer.accept(new TaskArtifactUpdateEvent(
                    "remote-task-1", artifact, "context-1", false, true, Map.of()));
            consumer.accept(new TaskStatusUpdateEvent(
                    "remote-task-1", new TaskStatus(TaskState.COMPLETED), "context-1", true, Map.of()));
            return null;
        }).when(a2aClient).sendStreamingMessage(any(), any(), any(), any());

        AgentTaskInvokeResponse sync = client.invoke(descriptor, request);
        AgentTaskInvokeResponse streaming = client.stream(descriptor, request, ignored -> {
        });
        client.submitAsync(descriptor, request);
        AgentTaskInvokeResponse async = client.queryAsyncStatus(descriptor, "remote-task-1").result();

        assertThat(sync).isNotNull();
        assertThat(streaming).isNotNull();
        assertThat(async).isNotNull();
        assertThat(List.of(sync, streaming, async))
                .allSatisfy(response -> {
                    assertThat(response.taskId()).isEqualTo("task-1");
                    assertThat(response.status()).isEqualTo("COMPLETED");
                    assertThat(response.structuredOutput()).containsEntry("remoteTaskId", "remote-task-1");
                    assertThat(response.artifactIds()).containsExactly("remote-artifact-1");
                });
    }

    @Test
    void streamingTerminalResponseContainsStructuredDataAndRemoteTaskId() throws Exception {
        A2AClient a2aClient = mock(A2AClient.class);
        OfficialA2aAgentClient client = new OfficialA2aAgentClient(
                new OfficialA2aResponseNormalizer(new ObjectMapper()), ignored -> a2aClient);
        Artifact artifact = artifact();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<io.a2a.spec.StreamingEventKind> consumer = invocation.getArgument(1);
            consumer.accept(new TaskArtifactUpdateEvent(
                    "remote-task-2", artifact, "context-1", false, true, Map.of()));
            consumer.accept(new TaskStatusUpdateEvent(
                    "remote-task-2", new TaskStatus(TaskState.COMPLETED), "context-1", true, Map.of()));
            return null;
        }).when(a2aClient).sendStreamingMessage(any(), any(), any(), any());

        List<ChildAgentStreamEvent> events = new ArrayList<>();
        AgentTaskInvokeResponse response = client.stream(descriptor(), request(), events::add);

        assertThat(response.structuredOutput()).containsEntry("remoteTaskId", "remote-task-2");
        assertThat(response.structuredOutput()).containsEntry("contentType", "listing");
        assertThat(events).anySatisfy(event -> {
            assertThat(event.terminal()).isTrue();
            assertThat(event.result()).isSameAs(response);
        });
    }

    private Artifact artifact() {
        return new Artifact(
                "remote-artifact-1", "listing", "listing result",
                List.of(new DataPart(Map.of("contentType", "listing", "listingCount", 2))), Map.of());
    }

    private RegisteredAgentDescriptor descriptor() {
        return new RegisteredAgentDescriptor(
                "listing-agent", "http://127.0.0.1:9999", "/.well-known/agent.json", "/a2a",
                AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD,
                new AgentCard("listing-agent", "listing-agent", "listing", "1.0.0", List.of(),
                        List.of("listing"), true, true, "http://127.0.0.1:9999/a2a",
                        List.of("text"), List.of("text", "application/json")));
    }

    private AgentTaskInvokeRequest request() {
        return new AgentTaskInvokeRequest(
                "session-1", "task-1", "parent-1", "trace-1", "supervisor-agent", "listing-agent",
                "listing.search", "listing", "find listings", Map.of("keyword", "浦东"),
                List.of(), List.of(), "json", "idem-1", true);
    }
}
