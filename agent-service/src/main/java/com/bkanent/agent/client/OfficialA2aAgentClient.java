package com.bkanent.agent.client;

import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.A2aAsyncTaskCreateResponse;
import com.bkanent.common.agent.A2aAsyncTaskStatusResponse;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import io.a2a.client.A2AClient;
import io.a2a.spec.A2AServerException;
import io.a2a.spec.Artifact;
import io.a2a.spec.EventKind;
import io.a2a.spec.GetTaskResponse;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendConfiguration;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.StreamingEventKind;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.UUID;

/**
 * Alibaba 官方 A2A 客户端。
 *
 * <p>该客户端是 Supervisor 唯一的 A2A 网络出口。Supervisor 内部请求和响应
 * 通过 {@link OfficialA2aMetadataMapper} 映射为官方 Message/Task/Artifact，
 * 不会作为自定义 HTTP JSON 包络发送。</p>
 */
@Primary
@Component
public class OfficialA2aAgentClient implements A2aAgentClient {

    private final ConcurrentMap<String, A2AClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AgentTaskInvokeRequest> taskRequests = new ConcurrentHashMap<>();

    @Override
    public AgentTaskInvokeResponse invoke(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        try {
            SendMessageResponse response = clientFor(descriptor).sendMessage(buildMessageSendParams(request, true));
            EventKind result = response.getResult();
            if (result instanceof Message message) {
                return responseFromText(descriptor, request, extractMessageText(message), "COMPLETED",
                        request.taskId(), Set.of(), null);
            }
            if (result instanceof Task task) {
                return responseFromTask(descriptor, request, task);
            }
            throw new IllegalStateException("official a2a invoke returned unsupported result for " + descriptor.agentId());
        } catch (A2AServerException exception) {
            throw new IllegalStateException("official a2a invoke failed for " + descriptor.agentId(), exception);
        }
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
            throw new UnsupportedOperationException("child agent does not advertise official A2A streaming: "
                    + (descriptor == null ? "unknown" : descriptor.agentId()));
        }
        CompletableFuture<AgentTaskInvokeResponse> result = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();
        Set<String> artifactIds = new LinkedHashSet<>();
        AtomicBoolean completed = new AtomicBoolean();
        try {
            clientFor(descriptor).sendStreamingMessage(
                    buildMessageSendParams(request, false),
                    event -> handleStreamingEvent(descriptor, request, eventConsumer, result, output,
                            artifactIds, completed, event),
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
                                      Set<String> artifactIds,
                                      AtomicBoolean completed,
                                      StreamingEventKind event) {
        if (event instanceof Message message) {
            String text = extractMessageText(message);
            appendOutput(output, text);
            emit(eventConsumer, "agent.delta", text, Map.of("source", "a2a.message"), false, null);
            return;
        }
        if (event instanceof TaskArtifactUpdateEvent artifactUpdate) {
            Artifact artifact = artifactUpdate.getArtifact();
            if (artifact != null && StringUtils.hasText(artifact.artifactId())) {
                artifactIds.add(artifact.artifactId());
            }
            String text = extractArtifactText(artifact);
            if (Boolean.FALSE.equals(artifactUpdate.isAppend())) {
                output.setLength(0);
            }
            appendOutput(output, text);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "a2a.artifact");
            if (artifact != null && StringUtils.hasText(artifact.artifactId())) {
                metadata.put("artifactId", artifact.artifactId());
            }
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
                completeFromStatus(descriptor, request, eventConsumer, result, output, artifactIds,
                        completed, status, statusUpdate.getTaskId(), statusText);
            }
            return;
        }
        if (event instanceof Task task) {
            artifactIds.addAll(extractArtifactIds(task));
            String taskOutput = extractTaskOutput(task);
            if (StringUtils.hasText(taskOutput)) {
                output.setLength(0);
                output.append(taskOutput);
            }
            String status = mapTaskState(task.getStatus() == null ? null : task.getStatus().state());
            emit(eventConsumer, "agent.progress", "Child agent task update", Map.of(
                    "source", "a2a.task", "childTaskId", task.getId(), "status", status), false, null);
            if (isTerminal(status)) {
                completeFromStatus(descriptor, request, eventConsumer, result, output, artifactIds,
                        completed, status, task.getId(), extractMessageText(
                                task.getStatus() == null ? null : task.getStatus().message()));
            }
        }
    }

    private void completeFromStatus(RegisteredAgentDescriptor descriptor,
                                    AgentTaskInvokeRequest request,
                                    Consumer<ChildAgentStreamEvent> eventConsumer,
                                    CompletableFuture<AgentTaskInvokeResponse> result,
                                    StringBuilder output,
                                    Set<String> artifactIds,
                                    AtomicBoolean completed,
                                    String status,
                                    String remoteTaskId,
                                    String errorMessage) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        if (!"COMPLETED".equalsIgnoreCase(status)) {
            AgentTaskInvokeResponse failedResponse = responseFromText(descriptor, request, output.toString(), status,
                    request.taskId(), artifactIds, errorMessage);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "a2a.status");
            metadata.put("status", status);
            if (StringUtils.hasText(errorMessage)) {
                metadata.put("errorMessage", errorMessage);
            }
            emit(eventConsumer, "agent.failed", "Child agent stream failed", metadata, true, failedResponse);
            result.complete(failedResponse);
            return;
        }
        AgentTaskInvokeResponse finalResponse = responseFromText(descriptor, request, output.toString(), status,
                request.taskId(), artifactIds, null);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "a2a.status");
        metadata.put("status", status);
        if (StringUtils.hasText(remoteTaskId)) {
            metadata.put("childTaskId", remoteTaskId);
        }
        emit(eventConsumer, "agent.completed", "Child agent stream completed", metadata, true, finalResponse);
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
            SendMessageResponse response = clientFor(descriptor).sendMessage(buildMessageSendParams(request, false));
            if (!(response.getResult() instanceof Task task)) {
                throw new IllegalStateException("official a2a async create did not return a task for "
                        + descriptor.agentId());
            }
            String asyncTaskId = task.getId();
            taskRequests.put(asyncTaskId, request);
            return new A2aAsyncTaskCreateResponse(
                    request.sessionId(),
                    request.taskId(),
                    descriptor.agentId(),
                    asyncTaskId,
                    mapTaskState(task.getStatus() == null ? null : task.getStatus().state()),
                    request.traceId()
            );
        } catch (A2AServerException exception) {
            throw new IllegalStateException("official a2a async create failed for " + descriptor.agentId(), exception);
        }
    }

    @Override
    public A2aAsyncTaskStatusResponse queryAsyncStatus(RegisteredAgentDescriptor descriptor, String asyncTaskId) {
        try {
            GetTaskResponse response = clientFor(descriptor).getTask(asyncTaskId);
            Task task = response.getResult();
            if (task == null) {
                throw new IllegalStateException("official a2a task not found: " + asyncTaskId);
            }
            AgentTaskInvokeRequest request = taskRequests.get(asyncTaskId);
            String status = mapTaskState(task.getStatus() == null ? null : task.getStatus().state());
            AgentTaskInvokeResponse result = "COMPLETED".equalsIgnoreCase(status)
                    ? responseFromTask(descriptor, request, task)
                    : null;
            String errorMessage = "FAILED".equalsIgnoreCase(status) && task.getStatus() != null
                    ? extractMessageText(task.getStatus().message()) : null;
            return new A2aAsyncTaskStatusResponse(
                    request == null ? null : request.sessionId(),
                    request == null ? null : request.taskId(),
                    descriptor.agentId(),
                    asyncTaskId,
                    status,
                    result,
                    "FAILED".equalsIgnoreCase(status) ? "OFFICIAL_A2A_TASK_FAILED" : null,
                    errorMessage,
                    request == null ? null : request.traceId()
            );
        } catch (A2AServerException exception) {
            throw new IllegalStateException("official a2a async status failed for " + descriptor.agentId(), exception);
        }
    }

    private A2AClient clientFor(RegisteredAgentDescriptor descriptor) {
        if (descriptor == null || !StringUtils.hasText(descriptor.agentId())) {
            throw new IllegalArgumentException("official A2A descriptor is required");
        }
        return clients.computeIfAbsent(descriptor.agentId(), ignored -> new A2AClient(resolveEndpoint(descriptor)));
    }

    private String resolveEndpoint(RegisteredAgentDescriptor descriptor) {
        if (descriptor.agentCard() != null && StringUtils.hasText(descriptor.agentCard().a2aEndpoint())) {
            return descriptor.agentCard().a2aEndpoint();
        }
        if (!StringUtils.hasText(descriptor.baseUrl()) || !StringUtils.hasText(descriptor.a2aPath())) {
            throw new IllegalArgumentException("official A2A endpoint is missing for " + descriptor.agentId());
        }
        return descriptor.baseUrl().endsWith("/") && descriptor.a2aPath().startsWith("/")
                ? descriptor.baseUrl().substring(0, descriptor.baseUrl().length() - 1) + descriptor.a2aPath()
                : descriptor.baseUrl() + descriptor.a2aPath();
    }

    private MessageSendParams buildMessageSendParams(AgentTaskInvokeRequest request, boolean blocking) {
        Map<String, Object> metadata = OfficialA2aMetadataMapper.toMetadata(request, !blocking);
        Message message = new Message(
                Message.Role.USER,
                List.of(new TextPart(resolveInstruction(request))),
                resolveMessageId(request),
                request.sessionId(),
                request.taskId(),
                List.of(),
                metadata
        );
        MessageSendConfiguration configuration = new MessageSendConfiguration(List.of("text"), null, null, blocking);
        return new MessageSendParams(message, configuration, metadata);
    }

    private String resolveMessageId(AgentTaskInvokeRequest request) {
        if (StringUtils.hasText(request.idempotencyKey())) {
            return request.idempotencyKey();
        }
        if (StringUtils.hasText(request.taskId())) {
            return request.taskId();
        }
        return UUID.randomUUID().toString();
    }

    private String resolveInstruction(AgentTaskInvokeRequest request) {
        if (request != null && StringUtils.hasText(request.instruction())) {
            return request.instruction().trim();
        }
        if (request != null && request.structuredContext() != null) {
            Object keyword = request.structuredContext().get("keyword");
            if (keyword instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return "";
    }

    private AgentTaskInvokeResponse responseFromTask(RegisteredAgentDescriptor descriptor,
                                                     AgentTaskInvokeRequest request,
                                                     Task task) {
        String status = mapTaskState(task.getStatus() == null ? null : task.getStatus().state());
        String output = extractTaskOutput(task);
        return responseFromText(descriptor, request, output, status,
                request == null || !StringUtils.hasText(request.taskId()) ? task.getId() : request.taskId(),
                extractArtifactIds(task),
                "FAILED".equalsIgnoreCase(status) && task.getStatus() != null
                        ? extractMessageText(task.getStatus().message()) : null);
    }

    private AgentTaskInvokeResponse responseFromText(RegisteredAgentDescriptor descriptor,
                                                      AgentTaskInvokeRequest request,
                                                      String output,
                                                      String status,
                                                      String taskId,
                                                      Set<String> artifactIds,
                                                      String errorMessage) {
        String normalizedOutput = output == null ? "" : output;
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("officialA2a", true);
        structuredOutput.put("output", normalizedOutput);
        if (StringUtils.hasText(errorMessage)) {
            structuredOutput.put("error", errorMessage);
        }
        return new AgentTaskInvokeResponse(
                request == null ? null : request.sessionId(),
                taskId,
                descriptor.agentId(),
                status,
                Map.copyOf(structuredOutput),
                artifactIds == null ? List.of() : List.copyOf(artifactIds),
                List.of(),
                normalizedOutput,
                request == null ? null : request.traceId()
        );
    }

    private Set<String> extractArtifactIds(Task task) {
        Set<String> ids = new LinkedHashSet<>();
        if (task != null && task.getArtifacts() != null) {
            task.getArtifacts().forEach(artifact -> {
                if (artifact != null && StringUtils.hasText(artifact.artifactId())) {
                    ids.add(artifact.artifactId());
                }
            });
        }
        return ids;
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

    private boolean isTerminal(String status) {
        return "COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)
                || "REJECTED".equalsIgnoreCase(status);
    }

    private String extractTaskOutput(Task task) {
        List<String> chunks = new ArrayList<>();
        if (task != null && task.getArtifacts() != null) {
            for (Artifact artifact : task.getArtifacts()) {
                String text = extractArtifactText(artifact);
                if (StringUtils.hasText(text)) {
                    chunks.add(text.trim());
                }
            }
        }
        if (!chunks.isEmpty()) {
            return String.join(System.lineSeparator(), chunks);
        }
        if (task != null && task.getHistory() != null && !task.getHistory().isEmpty()) {
            return extractMessageText(task.getHistory().get(task.getHistory().size() - 1));
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
}
