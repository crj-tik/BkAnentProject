package com.bkanent.agent.service;

import com.bkanent.agent.client.A2aAgentClient;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.A2aAsyncTaskCreateResponse;
import com.bkanent.common.agent.A2aAsyncTaskStatusResponse;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class A2aExecutionService {

    private final A2aAgentClient a2aAgentClient;
    private final SessionStreamService sessionStreamService;
    private final AgentPermissionService agentPermissionService;

    public A2aExecutionService(A2aAgentClient a2aAgentClient,
                               SessionStreamService sessionStreamService,
                               AgentPermissionService agentPermissionService) {
        this.a2aAgentClient = a2aAgentClient;
        this.sessionStreamService = sessionStreamService;
        this.agentPermissionService = agentPermissionService;
    }

    public AgentTaskInvokeResponse execute(RegisteredAgentDescriptor descriptor,
                                           AgentTaskInvokeRequest request,
                                           String phase,
                                           Map<String, Object> metadata) {
        agentPermissionService.assertCanInvokeChildAgent(descriptor, request);
        if (!shouldUseAsync(descriptor, request)) {
            return a2aAgentClient.invoke(descriptor, request);
        }
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
