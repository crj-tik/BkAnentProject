package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.Event;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendConfiguration;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficialA2aAgentExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final A2aOutputPolicy policy = A2aOutputPolicy.structured("listing");

    @Test
    void blockingExecutionEmitsOneStructuredTerminalArtifact() throws Exception {
        ReactAgent agent = mockAgent("{\"decision\":\"SUCCESS\",\"listingCount\":1,"
                + "\"summary\":\"找到 1 套房源\",\"nextHints\":[]}");
        OfficialA2aAgentExecutor executor = new OfficialA2aAgentExecutor(agent, objectMapper, policy);
        EventQueue queue = EventQueue.create();

        executor.execute(context("task-sync", Map.of(), List.of("text", "application/json")), queue);
        List<Event> events = drain(queue);

        List<TaskArtifactUpdateEvent> artifacts = events.stream()
                .filter(TaskArtifactUpdateEvent.class::isInstance)
                .map(TaskArtifactUpdateEvent.class::cast)
                .toList();
        assertThat(artifacts).hasSize(1);
        Artifact artifact = artifacts.get(0).getArtifact();
        assertThat(artifact.artifactId()).isEqualTo("listing-agent:task-sync:result");
        assertThat(artifact.parts()).hasSize(2);
        assertThat(artifact.parts().get(0)).isInstanceOf(DataPart.class);
        assertThat(((DataPart) artifact.parts().get(0)).getData())
                .containsEntry("contentType", "listing")
                .containsEntry("decision", "SUCCESS");
        assertThat(artifact.parts().get(1)).isInstanceOf(TextPart.class);
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                TaskStatusUpdateEvent.class,
                status -> assertThat(status.getStatus().state()).isEqualTo(TaskState.COMPLETED)));
        queue.close();
    }

    @Test
    void streamingExecutionEmitsProgressAndSingleTerminalResult() throws Exception {
        ReactAgent agent = mockAgent("{\"decision\":\"SUCCESS\",\"summary\":\"流式完成\"}");
        when(agent.stream(anyString(), any())).thenReturn(Flux.just(
                new StreamingOutput<>("{\"decision\":\"SUCCESS\",", "llm", "listing-agent", new OverAllState()),
                new StreamingOutput<>("\"summary\":\"流式完成\"}", "llm", "listing-agent", new OverAllState())));
        OfficialA2aAgentExecutor executor = new OfficialA2aAgentExecutor(agent, objectMapper, policy);
        EventQueue queue = EventQueue.create();

        executor.execute(context("task-stream", Map.of("isStreaming", true), List.of("text", "application/json")), queue);
        List<Event> events = drain(queue);

        List<TaskArtifactUpdateEvent> artifacts = events.stream()
                .filter(TaskArtifactUpdateEvent.class::isInstance)
                .map(TaskArtifactUpdateEvent.class::cast)
                .toList();
        assertThat(artifacts).as(artifacts.stream()
                        .map(event -> event.getArtifact().artifactId()).toList().toString())
                .hasSize(3);
        assertThat(artifacts.subList(0, 2).stream().map(event -> event.getArtifact().artifactId()))
                .containsExactly("listing-agent:task-stream:progress:1", "listing-agent:task-stream:progress:2");
        assertThat(artifacts.get(2).getArtifact().artifactId()).isEqualTo("listing-agent:task-stream:result");
        assertThat(artifacts.get(2).getArtifact().parts().get(0)).isInstanceOf(DataPart.class);
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                TaskStatusUpdateEvent.class,
                status -> assertThat(status.getStatus().state()).isEqualTo(TaskState.COMPLETED)));
        queue.close();
    }

    @Test
    void invalidJsonFailsWithoutCompletedResult() throws Exception {
        ReactAgent agent = mockAgent("not-json");
        OfficialA2aAgentExecutor executor = new OfficialA2aAgentExecutor(agent, objectMapper, policy);
        EventQueue queue = EventQueue.create();

        executor.execute(context("task-invalid", Map.of(), List.of("text", "application/json")), queue);
        List<Event> events = drain(queue);

        assertThat(events).noneMatch(TaskArtifactUpdateEvent.class::isInstance);
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                TaskStatusUpdateEvent.class,
                status -> assertThat(status.getStatus().state()).isEqualTo(TaskState.FAILED)));
        queue.close();
    }

    @Test
    void cancellationWinsOverLateAgentCompletion() throws Exception {
        ReactAgent agent = mockAgent("{\"decision\":\"SUCCESS\"}");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.of(new OverAllState(Map.of("output", new org.springframework.ai.chat.messages.AssistantMessage(
                    "{\"decision\":\"SUCCESS\"}"))));
        }).when(agent).invoke(anyString(), any());
        OfficialA2aAgentExecutor executor = new OfficialA2aAgentExecutor(agent, objectMapper, policy);
        EventQueue queue = EventQueue.create();
        RequestContext context = context("task-cancel", Map.of(), List.of("text", "application/json"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> future = worker.submit(() -> executor.execute(context, queue));

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        executor.cancel(context, queue);
        release.countDown();
        future.get(5, TimeUnit.SECONDS);
        worker.shutdownNow();

        List<Event> events = drain(queue);
        assertThat(events).noneMatch(event -> event instanceof TaskStatusUpdateEvent status
                && status.getStatus().state() == TaskState.COMPLETED);
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                TaskStatusUpdateEvent.class,
                status -> assertThat(status.getStatus().state()).isEqualTo(TaskState.CANCELED)));
        queue.close();
    }

    private ReactAgent mockAgent(String output) throws Exception {
        ReactAgent agent = mock(ReactAgent.class);
        when(agent.name()).thenReturn("listing-agent");
        when(agent.getOutputKey()).thenReturn("output");
        when(agent.invoke(anyString(), any())).thenReturn(Optional.of(new OverAllState(Map.of(
                "output", new org.springframework.ai.chat.messages.AssistantMessage(output)))));
        return agent;
    }

    private RequestContext context(String taskId, Map<String, Object> metadata,
                                   List<String> acceptedOutputModes) {
        Message message = new Message.Builder()
                .role(Message.Role.USER)
                .parts(List.of(new TextPart("查询房源")))
                .taskId(taskId)
                .contextId("context-" + taskId)
                .build();
        MessageSendParams params = new MessageSendParams(
                message,
                new MessageSendConfiguration(acceptedOutputModes, null, null, true),
                metadata);
        return new RequestContext.Builder()
                .setParams(params)
                .setTaskId(taskId)
                .setContextId("context-" + taskId)
                .build();
    }

    private List<Event> drain(EventQueue queue) throws Exception {
        List<Event> events = new ArrayList<>();
        Event event;
        while ((event = queue.dequeueEvent(0)) != null) {
            events.add(event);
        }
        return events;
    }
}
