package com.bkanent.agent.service;

import com.bkanent.agent.client.A2aAgentClient;
import com.bkanent.agent.client.ChildAgentStreamEvent;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.A2aAsyncTaskCreateResponse;
import com.bkanent.common.agent.A2aAsyncTaskStatusResponse;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class A2aExecutionService {

    private final A2aAgentClient a2aAgentClient;
    private final SessionStreamService sessionStreamService;
    private final AgentPermissionService agentPermissionService;
    private final DistributedAgentProperties distributedAgentProperties;

    public A2aExecutionService(A2aAgentClient a2aAgentClient,
                               SessionStreamService sessionStreamService,
                               AgentPermissionService agentPermissionService,
                               DistributedAgentProperties distributedAgentProperties) {
        this.a2aAgentClient = a2aAgentClient;
        this.sessionStreamService = sessionStreamService;
        this.agentPermissionService = agentPermissionService;
        this.distributedAgentProperties = distributedAgentProperties;
    }

    public AgentTaskInvokeResponse execute(RegisteredAgentDescriptor descriptor,
                                           AgentTaskInvokeRequest request,
                                           String phase,
                                           Map<String, Object> metadata) {
        agentPermissionService.assertCanInvokeChildAgent(descriptor, request);
        publish(
                request.sessionId(),
                request.taskId(),
                descriptor.agentId(),
                "agent.started",
                "Child agent started",
                lifecycleMetadata(metadata, phase, request, false),
                request.traceId()
        );
        try {
            if (shouldUseStreaming(descriptor, request)) {
                return executeStreaming(descriptor, request, phase, metadata);
            }
            if (!shouldUseAsync(descriptor, request)) {
                AgentTaskInvokeResponse response = a2aAgentClient.invoke(descriptor, request);
                publish(
                        request.sessionId(), request.taskId(), descriptor.agentId(), "agent.completed",
                        "Child agent completed", lifecycleMetadata(metadata, phase, request, true), request.traceId()
                );
                return response;
            }
            return executeAsync(descriptor, request, phase, metadata);
        } catch (RuntimeException exception) {
            publish(
                    request.sessionId(), request.taskId(), descriptor.agentId(), "agent.failed",
                    exception.getMessage() == null ? "Child agent failed" : exception.getMessage(),
                    extend(lifecycleMetadata(metadata, phase, request, true), Map.of("error", exception.getClass().getSimpleName())),
                    request.traceId()
            );
            throw exception;
        }
    }

    private AgentTaskInvokeResponse executeStreaming(RegisteredAgentDescriptor descriptor,
                                                      AgentTaskInvokeRequest request,
                                                      String phase,
                                                      Map<String, Object> metadata) {
        AtomicReference<AgentTaskInvokeResponse> terminalResponse = new AtomicReference<>();
        AtomicBoolean terminalEventSeen = new AtomicBoolean();
        AtomicReference<Long> rateWindowStartedAt = new AtomicReference<>(System.currentTimeMillis());
        AtomicReference<Integer> rateWindowCount = new AtomicReference<>(0);
        AgentTaskInvokeResponse response;
        try {
            response = a2aAgentClient.stream(descriptor, request, event -> {
                if (event == null) {
                    return;
                }
                if (event.result() != null) {
                    terminalResponse.set(event.result());
                }
                if (isRateLimited(event, rateWindowStartedAt, rateWindowCount)) {
                    return;
                }
                if (event.terminal()) {
                    terminalEventSeen.set(true);
                }
                Map<String, Object> eventMetadata = lifecycleMetadata(metadata, phase, request, event.terminal());
                eventMetadata.putAll(sanitizeMetadata(event.metadata()));
                String content = limitContent(event.content(), event.eventType());
                publish(
                        request.sessionId(),
                        request.taskId(),
                        descriptor.agentId(),
                        event.eventType(),
                        content,
                        eventMetadata,
                        request.traceId()
                );
            });
        } catch (UnsupportedOperationException exception) {
            return executeAsyncOrBlockingFallback(descriptor, request, phase, metadata);
        }
        AgentTaskInvokeResponse finalResponse = terminalResponse.get() == null ? response : terminalResponse.get();
        if (!terminalEventSeen.get()) {
            publish(
                    request.sessionId(), request.taskId(), descriptor.agentId(), "agent.completed",
                    "Child agent stream completed", lifecycleMetadata(metadata, phase, request, true), request.traceId()
            );
        }
        return finalResponse;
    }

    private AgentTaskInvokeResponse executeAsyncOrBlockingFallback(RegisteredAgentDescriptor descriptor,
                                                                    AgentTaskInvokeRequest request,
                                                                    String phase,
                                                                    Map<String, Object> metadata) {
        if (shouldUseAsync(descriptor, request)) {
            return executeAsync(descriptor, request, phase, metadata);
        }
        AgentTaskInvokeResponse response = a2aAgentClient.invoke(descriptor, request);
        publish(
                request.sessionId(), request.taskId(), descriptor.agentId(), "agent.completed",
                "Child agent completed", lifecycleMetadata(metadata, phase, request, true), request.traceId()
        );
        return response;
    }

    private AgentTaskInvokeResponse executeAsync(RegisteredAgentDescriptor descriptor,
                                                 AgentTaskInvokeRequest request,
                                                 String phase,
                                                 Map<String, Object> metadata) {
        A2aAsyncTaskCreateResponse accepted = a2aAgentClient.submitAsync(descriptor, request);
        publish(
                request.sessionId(),
                request.taskId(),
                descriptor.agentId(),
                "a2a.async.accepted",
                "Child async task accepted",
                extend(metadata, Map.of(
                        "phase", phase,
                        "asyncTaskId", accepted.asyncTaskId(),
                        "status", accepted.status()
                )),
                request.traceId()
        );
        String lastStatus = accepted.status();
        while (true) {
            sleepQuietly(1000L);
            A2aAsyncTaskStatusResponse status = a2aAgentClient.queryAsyncStatus(descriptor, accepted.asyncTaskId());
            if (!Objects.equals(lastStatus, status.status())) {
                publish(
                        request.sessionId(),
                        request.taskId(),
                        descriptor.agentId(),
                        "a2a.async.status",
                        "Child async task status updated",
                        extend(metadata, Map.of(
                                "phase", phase,
                                "asyncTaskId", accepted.asyncTaskId(),
                                "status", status.status()
                        )),
                        request.traceId()
                );
                lastStatus = status.status();
            }
            if ("COMPLETED".equalsIgnoreCase(status.status()) || "completed".equalsIgnoreCase(status.status())) {
                publish(
                        request.sessionId(),
                        request.taskId(),
                        descriptor.agentId(),
                        "a2a.async.completed",
                        "Child async task completed",
                        extend(metadata, Map.of(
                                "phase", phase,
                                "asyncTaskId", accepted.asyncTaskId(),
                                "status", status.status()
                        )),
                        request.traceId()
                );
                if (status.result() == null) {
                    throw new IllegalStateException("async task completed without result");
                }
                publish(
                        request.sessionId(), request.taskId(), descriptor.agentId(), "agent.completed",
                        "Child async agent completed", lifecycleMetadata(metadata, phase, request, true), request.traceId()
                );
                return status.result();
            }
            if ("FAILED".equalsIgnoreCase(status.status()) || "failed".equalsIgnoreCase(status.status())) {
                publish(
                        request.sessionId(),
                        request.taskId(),
                        descriptor.agentId(),
                        "a2a.async.failed",
                        "Child async task failed",
                        extend(metadata, Map.of(
                                "phase", phase,
                                "asyncTaskId", accepted.asyncTaskId(),
                                "status", status.status(),
                                "errorMessage", status.errorMessage() == null ? "" : status.errorMessage()
                        )),
                        request.traceId()
                );
                throw new IllegalStateException(status.errorMessage() == null ? "async child task failed" : status.errorMessage());
            }
        }
    }

    private boolean shouldUseStreaming(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        return Boolean.TRUE.equals(request.stream())
                && descriptor.agentCard() != null
                && Boolean.TRUE.equals(descriptor.agentCard().supportsStreaming())
                && distributedAgentProperties.getStreaming().isEnabled()
                && a2aAgentClient.supportsStreaming(descriptor, request);
    }

    private boolean shouldUseAsync(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        if (descriptor.agentCard() == null || !Boolean.TRUE.equals(descriptor.agentCard().supportsAsyncTask())) {
            return false;
        }
        if (Boolean.TRUE.equals(request.stream())) {
            return true;
        }
        if (request.structuredContext() == null) {
            return false;
        }
        return Boolean.TRUE.equals(request.structuredContext().get("forceAsyncA2a"))
                || Boolean.TRUE.equals(request.structuredContext().get("requestStream"));
    }

    private Map<String, Object> extend(Map<String, Object> metadata, Map<String, Object> addition) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        merged.putAll(addition);
        return merged;
    }

    private boolean isRateLimited(ChildAgentStreamEvent event,
                                  AtomicReference<Long> rateWindowStartedAt,
                                  AtomicReference<Integer> rateWindowCount) {
        if (!"agent.delta".equals(event.eventType())) {
            return false;
        }
        long now = System.currentTimeMillis();
        long started = rateWindowStartedAt.get();
        if (now - started >= 1000L) {
            rateWindowStartedAt.set(now);
            rateWindowCount.set(0);
        }
        int count = rateWindowCount.updateAndGet(value -> value + 1);
        return count > Math.max(1, distributedAgentProperties.getStreaming().getMaxDeltaEventsPerSecond());
    }

    private String limitContent(String content, String eventType) {
        String normalized = content == null ? "" : content;
        int limit = "agent.delta".equals(eventType)
                ? distributedAgentProperties.getStreaming().getMaxDeltaChars()
                : distributedAgentProperties.getStreaming().getMaxMetadataChars();
        if (limit <= 0 || normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit) + "…";
    }

    private Map<String, Object> sanitizeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new HashMap<>();
        metadata.forEach((key, value) -> {
            if (key == null || "raw".equalsIgnoreCase(key) || "reasoning".equalsIgnoreCase(key)
                    || "arguments".equalsIgnoreCase(key) || "input".equalsIgnoreCase(key)) {
                return;
            }
            if (value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized;
    }

    private Map<String, Object> lifecycleMetadata(Map<String, Object> metadata,
                                                  String phase,
                                                  AgentTaskInvokeRequest request,
                                                  boolean terminal) {
        Map<String, Object> lifecycle = extend(metadata, Map.of(
                "phase", phase == null ? "child_agent" : phase,
                "terminal", terminal,
                "visibility", "progress"
        ));
        addIfText(lifecycle, "childRunId", request.structuredContext(), "childRunId");
        addIfText(lifecycle, "parentTaskId", request.parentTaskId());
        addIfText(lifecycle, "branchId", request.structuredContext(), "branchId");
        return lifecycle;
    }

    private void addIfText(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.putIfAbsent(key, value);
        }
    }

    private void addIfText(Map<String, Object> metadata,
                           String key,
                           Map<String, Object> source,
                           String sourceKey) {
        if (source == null) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value != null && !String.valueOf(value).isBlank()) {
            metadata.putIfAbsent(key, value);
        }
    }

    private void publish(String sessionId,
                         String taskId,
                         String agentId,
                         String eventType,
                         String content,
                         Map<String, Object> metadata,
                         String traceId) {
        sessionStreamService.publish(new SessionStreamEvent(
                sessionId,
                taskId,
                agentId,
                eventType,
                content,
                metadata,
                traceId,
                System.currentTimeMillis()
        ));
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("async child task polling interrupted", exception);
        }
    }
}
