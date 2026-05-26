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
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
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

@Component
public class OfficialA2aAgentClient implements A2aAgentClient {

    private static final String OUTPUT_KEY = "output";

    private final OfficialSupervisorGraphThreadResolver threadResolver;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, A2aRemoteAgent> remoteAgents = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, A2AClient> officialClients = new ConcurrentHashMap<>();

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
