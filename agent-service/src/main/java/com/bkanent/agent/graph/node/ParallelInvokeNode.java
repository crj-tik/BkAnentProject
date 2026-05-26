package com.bkanent.agent.graph.node;

import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.service.A2aExecutionService;
import com.bkanent.agent.service.SupervisorAgentRoutingService;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class ParallelInvokeNode {

    private final BuildInvokeRequestNode buildInvokeRequestNode;
    private final A2aExecutionService a2aExecutionService;
    private final SessionStreamService sessionStreamService;
    private final SupervisorAgentRoutingService supervisorAgentRoutingService;

    public ParallelInvokeNode(BuildInvokeRequestNode buildInvokeRequestNode,
                              A2aExecutionService a2aExecutionService,
                              SessionStreamService sessionStreamService,
                              SupervisorAgentRoutingService supervisorAgentRoutingService) {
        this.buildInvokeRequestNode = buildInvokeRequestNode;
        this.a2aExecutionService = a2aExecutionService;
        this.sessionStreamService = sessionStreamService;
        this.supervisorAgentRoutingService = supervisorAgentRoutingService;
    }

    public AgentTaskInvokeResponse invoke(SupervisorTaskRequest request,
                                          com.bkanent.agent.graph.SupervisorGraphState graphState) {
        List<String> parallelDomains = graphState.parallelDomains();
        List<CompletableFuture<AgentTaskInvokeResponse>> futures = parallelDomains.stream()
                .map(domain -> CompletableFuture.supplyAsync(() -> {
                    RegisteredAgentDescriptor descriptor = selectAgent(domain, request.userMessage(), graphState.sharedContext());
                    publish(graphState.sessionId(), graphState.taskId(), descriptor.agentId(),
                            "handoff.started", "Invoking child agent in parallel",
                            Map.of("domain", domain), graphState.traceId());
                    AgentTaskInvokeRequest invokeRequest = buildInvokeRequestNode.build(
                            withDomain(request, domain),
                            graphState.withIntent(resolveIntent(domain), domain, "parallel"),
                            descriptor.agentId(),
                            null,
                            0
                    );
                    AgentTaskInvokeResponse response = a2aExecutionService.execute(
                            descriptor,
                            invokeRequest,
                            "parallel_agent",
                            Map.of("domain", domain, "targetAgentId", descriptor.agentId())
                    );
                    publish(graphState.sessionId(), graphState.taskId(), descriptor.agentId(),
                            "handoff.completed", "Parallel child agent returned",
                            Map.of(
                                    "domain", domain,
                                    "status", response.status(),
                                    "userId", graphState.userId() == null ? "" : graphState.userId()
                            ), graphState.traceId());
                    return response;
                }))
                .toList();
        List<AgentTaskInvokeResponse> responses = futures.stream().map(CompletableFuture::join).toList();
        return mergeParallelResponses(graphState.sessionId(), graphState.taskId(), graphState.traceId(), parallelDomains, responses);
    }

    private SupervisorTaskRequest withDomain(SupervisorTaskRequest request, String domain) {
        Map<String, Object> context = new LinkedHashMap<>(request.context() == null ? Map.of() : request.context());
        context.put("domain", domain);
        return new SupervisorTaskRequest(
                request.sessionId(),
                request.userId(),
                request.requestId(),
                request.traceId(),
                request.userMessage(),
                context,
                request.channel(),
                request.stream()
        );
    }

    private RegisteredAgentDescriptor selectAgent(String domain, String message, Map<String, Object> context) {
        return supervisorAgentRoutingService.selectAgent(domain, message, context);
    }

    private AgentTaskInvokeResponse mergeParallelResponses(String sessionId,
                                                           String taskId,
                                                           String traceId,
                                                           List<String> parallelDomains,
                                                           List<AgentTaskInvokeResponse> responses) {
        Map<String, Object> mergedOutput = new LinkedHashMap<>();
        List<String> artifactIds = new ArrayList<>();
        List<String> nextHints = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        for (int index = 0; index < parallelDomains.size(); index++) {
            String domain = parallelDomains.get(index);
            AgentTaskInvokeResponse response = responses.get(index);
            mergedOutput.put(domain + "Output", response.structuredOutput());
            if (response.structuredOutput() != null) {
                mergedOutput.putAll(extractNamespacedScalars(domain, response.structuredOutput()));
            }
            if (response.artifactIds() != null) {
                artifactIds.addAll(response.artifactIds());
            }
            if (response.nextHints() != null) {
                nextHints.addAll(response.nextHints());
            }
            if (StringUtils.hasText(response.summary())) {
                summaries.add(domain + ": " + response.summary());
            }
        }
        mergedOutput.put("parallelDomains", parallelDomains);
        mergedOutput.put("contentType", "parallel_result");
        mergedOutput.put("mergeSummary", buildMergeSummary(parallelDomains, responses));
        return new AgentTaskInvokeResponse(
                sessionId,
                taskId,
                "parallel-supervisor",
                "COMPLETED",
                mergedOutput,
                List.copyOf(new LinkedHashSet<>(artifactIds)),
                List.copyOf(new LinkedHashSet<>(nextHints)),
                summaries.isEmpty() ? "Parallel child agents finished processing." : String.join(" | ", summaries),
                traceId
        );
    }

    private Map<String, Object> extractNamespacedScalars(String domain, Map<String, Object> output) {
        Map<String, Object> scalars = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof Map<?, ?>) && !(value instanceof Collection<?>)) {
                scalars.put(domain + capitalize(entry.getKey()), value);
            }
        }
        return scalars;
    }

    private Map<String, Object> buildMergeSummary(List<String> parallelDomains,
                                                  List<AgentTaskInvokeResponse> responses) {
        List<Map<String, Object>> mergedChildren = new ArrayList<>();
        for (int index = 0; index < parallelDomains.size(); index++) {
            AgentTaskInvokeResponse response = responses.get(index);
            mergedChildren.add(Map.of(
                    "domain", parallelDomains.get(index),
                    "agentId", response.agentId(),
                    "status", response.status(),
                    "summary", response.summary() == null ? "" : response.summary(),
                    "artifactCount", response.artifactIds() == null ? 0 : response.artifactIds().size()
            ));
        }
        return Map.of(
                "type", "parallel_merge",
                "domains", parallelDomains,
                "children", mergedChildren,
                "mergedAt", System.currentTimeMillis()
        );
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

    private String resolveIntent(String domain) {
        return switch (domain) {
            case "marketing" -> "marketing.generate_copy";
            case "media" -> "media.generate_video_task";
            case "trade" -> "trade.feasibility_analysis";
            case "contract" -> "contract.risk_review";
            default -> "listing.search";
        };
    }

    private boolean containsListingIntent(String message) {
        return message.contains("房源")
                || message.contains("找房")
                || message.contains("小区")
                || message.contains("listing")
                || message.contains("房子");
    }

    private String capitalize(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
