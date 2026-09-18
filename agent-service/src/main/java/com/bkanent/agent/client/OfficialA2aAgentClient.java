package com.bkanent.agent.client;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.a2a.A2aRemoteAgent;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardProvider;
import com.alibaba.cloud.ai.graph.agent.a2a.RemoteAgentCardProvider;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphThreadResolver;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.A2aAsyncTaskCreateResponse;
import com.bkanent.common.agent.A2aAsyncTaskStatusResponse;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.client.A2AClient;
import io.a2a.spec.A2AServerException;
import io.a2a.spec.Artifact;
import io.a2a.spec.EventKind;
import io.a2a.spec.GetTaskResponse;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendConfiguration;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.StreamingEventKind;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Component
public class OfficialA2aAgentClient implements A2aAgentClient {

    private static final String OUTPUT_KEY = "output";

    private final OfficialSupervisorGraphThreadResolver threadResolver;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, A2aRemoteAgent> remoteAgents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, A2AClient> officialClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, A2AClient> streamingClients = new ConcurrentHashMap<>();

    public OfficialA2aAgentClient(OfficialSupervisorGraphThreadResolver threadResolver,
                                  ObjectMapper objectMapper) {
        this.threadResolver = threadResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentTaskInvokeResponse invoke(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        if (usesStructuredOfficialPayload(descriptor)) {
            return invokeByStandardClient(descriptor, request);
        }
        A2aRemoteAgent remoteAgent = remoteAgents.computeIfAbsent(descriptor.agentId(), ignored -> buildRemoteAgent(descriptor));
        RunnableConfig runnableConfig = threadResolver.resolve(request.sessionId(), request.taskId());
        String instruction = resolveInstruction(request);
        Optional<OverAllState> outputState;
        try {
            outputState = remoteAgent.invoke(instruction, runnableConfig);
        } catch (GraphRunnerException exception) {
            throw new IllegalStateException("official a2a invoke failed for " + descriptor.agentId(), exception);
        }
        String output = outputState
                .flatMap(state -> state.value(OUTPUT_KEY, String.class))
                .map(String::trim)
                .orElse("");
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("officialA2a", true);
        structuredOutput.put("output", output);
        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                descriptor.agentId(),
                "completed",
                structuredOutput,
                List.of(),
                List.of(),
                output,
                request.traceId()
        );
    }

    @Override
    public boolean supportsStreaming(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        return descriptor != null
                && descriptor.agentCard() != null
                && Boolean.TRUE.equals(descriptor.agentCard().supportsStreaming());
    }

    @Override
    public AgentTaskInvokeResponse stream(RegisteredAgentDescriptor descriptor,
                                          AgentTaskInvokeRequest request,
                                          Consumer<ChildAgentStreamEvent> eventConsumer) {
        if (!supportsStreaming(descriptor, request)) {
            throw new UnsupportedOperationException("child agent does not advertise streaming: " + descriptor.agentId());
        }
        CompletableFuture<AgentTaskInvokeResponse> result = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();
        AtomicBoolean completed = new AtomicBoolean();
        A2AClient client = streamingClients.computeIfAbsent(
                descriptor.agentId(), ignored -> new A2AClient(descriptor.baseUrl() + descriptor.a2aTaskStreamPath()));
        try {
            client.sendStreamingMessage(
                    buildMessageSendParams(request, false),
                    event -> handleStreamingEvent(descriptor, request, eventConsumer, result, output, completed, event),
                    error -> result.completeExceptionally(new IllegalStateException(
                            "official a2a streaming error for " + descriptor.agentId() + ": " + error)),
                    () -> result.completeExceptionally(new IllegalStateException(
                            "official a2a streaming connection failed for " + descriptor.agentId()))
            );
        } catch (A2AServerException exception) {
            result.completeExceptionally(new IllegalStateException(
                    "official a2a streaming invoke failed for " + descriptor.agentId(), exception));
        }
        try {
            return result.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("official a2a streaming invoke failed", cause);
        }
    }

    private void handleStreamingEvent(RegisteredAgentDescriptor descriptor,
                                      AgentTaskInvokeRequest request,
                                      Consumer<ChildAgentStreamEvent> eventConsumer,
                                      CompletableFuture<AgentTaskInvokeResponse> result,
                                      StringBuilder output,
                                      AtomicBoolean completed,
                                      StreamingEventKind event) {
        if (event instanceof Message message) {
            String text = extractMessageText(message);
            appendOutput(output, text);
            emit(eventConsumer, "agent.delta", text, Map.of("source", "a2a.message"), false, null);
            return;
        }
        if (event instanceof TaskArtifactUpdateEvent artifactUpdate) {
            String text = extractArtifactText(artifactUpdate.getArtifact());
            if (Boolean.FALSE.equals(artifactUpdate.isAppend())) {
                output.setLength(0);
            }
            appendOutput(output, text);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "a2a.artifact");
            metadata.put("artifactId", artifactUpdate.getArtifact().artifactId());
            if (artifactUpdate.isAppend() != null) {
                metadata.put("append", artifactUpdate.isAppend());
            }
            if (artifactUpdate.isLastChunk() != null) {
                metadata.put("lastChunk", artifactUpdate.isLastChunk());
            }
            emit(eventConsumer, "agent.delta", text, metadata, false, null);
            return;
        }
        if (event instanceof TaskStatusUpdateEvent statusUpdate) {
            String status = mapTaskState(statusUpdate.getStatus() == null ? null : statusUpdate.getStatus().state());
            String statusText = statusUpdate.getStatus() == null
                    ? "Child agent status updated"
                    : extractMessageText(statusUpdate.getStatus().message());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "a2a.status");
            metadata.put("childTaskId", statusUpdate.getTaskId());
            metadata.put("status", status);
            emit(eventConsumer, "agent.progress", statusText, metadata, false, null);
            if (statusUpdate.isFinal()) {
                completeFromStatus(descriptor, request, eventConsumer, result, output, completed, status);
            }
            return;
        }
        if (event instanceof Task task) {
            String taskOutput = extractTaskOutput(task);
            if (StringUtils.hasText(taskOutput)) {
                output.setLength(0);
                output.append(taskOutput);
            }
            String status = mapTaskState(task.getStatus() == null ? null : task.getStatus().state());
            emit(eventConsumer, "agent.progress", "Child agent task update", Map.of(
                    "source", "a2a.task", "childTaskId", task.getId(), "status", status), false, null);
            if ("COMPLETED".equalsIgnoreCase(status)
                    || "FAILED".equalsIgnoreCase(status)
                    || "CANCELLED".equalsIgnoreCase(status)
                    || "REJECTED".equalsIgnoreCase(status)) {
                completeFromStatus(descriptor, request, eventConsumer, result, output, completed, status);
            }
        }
    }

    private void completeFromStatus(RegisteredAgentDescriptor descriptor,
                                    AgentTaskInvokeRequest request,
                                    Consumer<ChildAgentStreamEvent> eventConsumer,
                                    CompletableFuture<AgentTaskInvokeResponse> result,
                                    StringBuilder output,
                                    AtomicBoolean completed,
                                    String status) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        if (!"COMPLETED".equalsIgnoreCase(status)) {
            result.completeExceptionally(new IllegalStateException(
                    "official a2a child task ended with status " + status));
            return;
        }
        AgentTaskInvokeResponse finalResponse = parseStructuredResponse(descriptor, output.toString(), request.taskId());
        emit(eventConsumer, "agent.completed", "Child agent stream completed", Map.of(
                "source", "a2a.status", "status", status), true, finalResponse);
        result.complete(finalResponse);
    }

    private void emit(Consumer<ChildAgentStreamEvent> eventConsumer,
                      String eventType,
                      String content,
                      Map<String, Object> metadata,
                      boolean terminal,
                      AgentTaskInvokeResponse result) {
        eventConsumer.accept(new ChildAgentStreamEvent(
                eventType,
                content == null ? "" : content,
                metadata == null ? Map.of() : Map.copyOf(metadata),
                terminal,
                result
        ));
    }

    private void appendOutput(StringBuilder output, String text) {
        if (StringUtils.hasText(text)) {
            output.append(text);
        }
    }

    private String extractArtifactText(Artifact artifact) {
        if (artifact == null || artifact.parts() == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        artifact.parts().forEach(part -> {
            if (part instanceof TextPart textPart && StringUtils.hasText(textPart.getText())) {
                parts.add(textPart.getText());
            }
        });
        return String.join(System.lineSeparator(), parts);
    }

    @Override
    public A2aAsyncTaskCreateResponse submitAsync(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        try {
            SendMessageResponse response = officialClients.computeIfAbsent(descriptor.agentId(), ignored -> new A2AClient(descriptor.baseUrl() + descriptor.agentCardPath()))
                    .sendMessage(buildMessageSendParams(request, false));
            EventKind result = response.getResult();
            if (result instanceof Task task) {
                return new A2aAsyncTaskCreateResponse(
                        task.getId(),
                        mapTaskState(task.getStatus() == null ? null : task.getStatus().state()),
                        descriptor.agentId(),
                        request.sessionId(),
                        request.taskId(),
                        request.traceId()
                );
            }
            throw new IllegalStateException("official a2a async task did not return task result");
        } catch (A2AServerException exception) {
            throw new IllegalStateException("official a2a async create failed for " + descriptor.agentId(), exception);
        }
    }

    @Override
    public A2aAsyncTaskStatusResponse queryAsyncStatus(RegisteredAgentDescriptor descriptor, String asyncTaskId) {
        try {
            GetTaskResponse response = officialClients.computeIfAbsent(descriptor.agentId(), ignored -> new A2AClient(descriptor.baseUrl() + descriptor.agentCardPath()))
                    .getTask(asyncTaskId);
            Task task = response.getResult();
            if (task == null) {
                throw new IllegalStateException("official a2a task not found: " + asyncTaskId);
            }
            String status = mapTaskState(task.getStatus() == null ? null : task.getStatus().state());
            AgentTaskInvokeResponse result = null;
            if ("COMPLETED".equalsIgnoreCase(status)) {
                String output = extractTaskOutput(task);
                result = parseStructuredResponse(descriptor, output, asyncTaskId);
            }
            String errorMessage = null;
            if ("FAILED".equalsIgnoreCase(status) && task.getStatus() != null && task.getStatus().message() != null) {
                errorMessage = extractMessageText(task.getStatus().message());
            }
            return new A2aAsyncTaskStatusResponse(
                    null,
                    asyncTaskId,
                    descriptor.agentId(),
                    asyncTaskId,
                    status,
                    result,
                    "FAILED".equalsIgnoreCase(status) ? "OFFICIAL_A2A_TASK_FAILED" : null,
                    errorMessage,
                    null
            );
        } catch (A2AServerException exception) {
            throw new IllegalStateException("official a2a async status failed for " + descriptor.agentId(), exception);
        }
    }

    private A2aRemoteAgent buildRemoteAgent(RegisteredAgentDescriptor descriptor) {
        String cardUrl = descriptor.baseUrl() + descriptor.agentCardPath();
        AgentCardProvider provider = RemoteAgentCardProvider.newProvider(cardUrl);
        return A2aRemoteAgent.builder()
                .name(descriptor.agentCard().name())
                .description(descriptor.agentCard().description())
                .agentCardProvider(provider)
                .outputKey(OUTPUT_KEY)
                .streaming(Boolean.TRUE.equals(descriptor.agentCard().supportsStreaming()))
                .shareState(true)
                .build();
    }

    private AgentTaskInvokeResponse invokeByStandardClient(RegisteredAgentDescriptor descriptor,
                                                           AgentTaskInvokeRequest request) {
        try {
            SendMessageResponse response = officialClients.computeIfAbsent(descriptor.agentId(), ignored -> new A2AClient(descriptor.baseUrl() + descriptor.agentCardPath()))
                    .sendMessage(buildMessageSendParams(request, true));
            EventKind result = response.getResult();
            if (result instanceof Message message) {
                return parseStructuredResponse(descriptor, extractMessageText(message), request.taskId());
            }
            if (result instanceof Task task) {
                return parseStructuredResponse(descriptor, extractTaskOutput(task), request.taskId());
            }
            throw new IllegalStateException("official a2a invoke returned unsupported result for " + descriptor.agentId());
        } catch (A2AServerException exception) {
            throw new IllegalStateException("official a2a invoke failed for " + descriptor.agentId(), exception);
        }
    }

    private String resolveInstruction(AgentTaskInvokeRequest request) {
        if (StringUtils.hasText(request.instruction())) {
            return request.instruction().trim();
        }
        if (request.structuredContext() != null) {
            Object keyword = request.structuredContext().get("keyword");
            if (keyword instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return "";
    }

    private MessageSendParams buildMessageSendParams(AgentTaskInvokeRequest request, boolean blocking) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("threadId", StringUtils.hasText(request.taskId()) ? request.taskId() : request.sessionId());
        addMetadata(metadata, "sessionId", request.sessionId());
        addMetadata(metadata, "taskId", request.taskId());
        addMetadata(metadata, "traceId", request.traceId());
        addMetadata(metadata, "sourceAgentId", request.sourceAgentId());
        addMetadata(metadata, "targetAgentId", request.targetAgentId());
        addMetadata(metadata, "intent", request.intent());
        addMetadata(metadata, "domain", request.domain());
        if (request.structuredContext() != null && !request.structuredContext().isEmpty()) {
            metadata.put("structuredContext", request.structuredContext());
        }
        Message message = new Message(
                Message.Role.USER,
                List.of(new TextPart(resolveInstruction(request))),
                request.idempotencyKey(),
                request.sessionId(),
                request.taskId(),
                List.of(),
                metadata
        );
        MessageSendConfiguration configuration = new MessageSendConfiguration(List.of("text"), null, null, blocking);
        return new MessageSendParams(message, configuration, metadata);
    }

    private void addMetadata(Map<String, Object> metadata, String key, String value) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, value);
        }
    }

    private String mapTaskState(TaskState state) {
        if (state == null) {
            return "UNKNOWN";
        }
        return switch (state) {
            case SUBMITTED -> "SUBMITTED";
            case WORKING -> "RUNNING";
            case INPUT_REQUIRED, AUTH_REQUIRED -> "WAITING";
            case COMPLETED -> "COMPLETED";
            case CANCELED -> "CANCELLED";
            case FAILED -> "FAILED";
            case REJECTED -> "REJECTED";
            case UNKNOWN -> "UNKNOWN";
        };
    }

    private String extractTaskOutput(Task task) {
        List<String> chunks = new ArrayList<>();
        if (task.getArtifacts() != null) {
            for (Artifact artifact : task.getArtifacts()) {
                if (artifact.parts() == null) {
                    continue;
                }
                artifact.parts().forEach(part -> {
                    if (part instanceof TextPart textPart && StringUtils.hasText(textPart.getText())) {
                        chunks.add(textPart.getText().trim());
                    }
                });
            }
        }
        if (!chunks.isEmpty()) {
            return String.join(System.lineSeparator(), chunks);
        }
        if (task.getHistory() != null && !task.getHistory().isEmpty()) {
            Message lastMessage = task.getHistory().get(task.getHistory().size() - 1);
            return extractMessageText(lastMessage);
        }
        return "";
    }

    private String extractMessageText(Message message) {
        if (message == null || message.getParts() == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        message.getParts().forEach(part -> {
            if (part instanceof TextPart textPart && StringUtils.hasText(textPart.getText())) {
                parts.add(textPart.getText().trim());
            }
        });
        return String.join(System.lineSeparator(), parts);
    }

    private AgentTaskInvokeResponse parseStructuredResponse(RegisteredAgentDescriptor descriptor,
                                                            String output,
                                                            String fallbackTaskId) {
        if (StringUtils.hasText(output)) {
            try {
                AgentTaskInvokeResponse parsed = objectMapper.readValue(output, AgentTaskInvokeResponse.class);
                if (parsed != null) {
                    return parsed;
                }
            } catch (JsonProcessingException ignored) {
                // Fallback to generic response when the server returns plain text.
            }
        }
        return new AgentTaskInvokeResponse(
                null,
                fallbackTaskId,
                descriptor.agentId(),
                "completed",
                Map.of("officialA2a", true, "output", output == null ? "" : output),
                List.of(),
                List.of(),
                output == null ? "" : output,
                null
        );
    }

    private boolean usesStructuredOfficialPayload(RegisteredAgentDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        if ("structured".equalsIgnoreCase(descriptor.officialPayloadMode())) {
            return true;
        }
        if ("plain".equalsIgnoreCase(descriptor.officialPayloadMode())) {
            return false;
        }
        return descriptor.agentCard() != null
                && descriptor.agentCard().supportedDomains() != null
                && descriptor.agentCard().supportedDomains().stream()
                .anyMatch(domain -> !"listing".equalsIgnoreCase(domain));
    }
}
