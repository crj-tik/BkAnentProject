package com.bkanent.agent.graph.node;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.service.A2aExecutionService;
import com.bkanent.agent.service.SupervisorAgentRoutingService;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.A2aInvokeSupport;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.AgentHandoffPacket;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class HandoffNode {

    private final A2aExecutionService a2aExecutionService;
    private final DistributedAgentProperties distributedAgentProperties;
    private final PersistArtifactsNode persistArtifactsNode;
    private final MemoryStoreClient memoryStoreClient;
    private final SessionStreamService sessionStreamService;
    private final SupervisorAgentRoutingService supervisorAgentRoutingService;

    public HandoffNode(A2aExecutionService a2aExecutionService,
                       DistributedAgentProperties distributedAgentProperties,
                       PersistArtifactsNode persistArtifactsNode,
                       MemoryStoreClient memoryStoreClient,
                       SessionStreamService sessionStreamService,
                       SupervisorAgentRoutingService supervisorAgentRoutingService) {
        this.a2aExecutionService = a2aExecutionService;
        this.distributedAgentProperties = distributedAgentProperties;
        this.persistArtifactsNode = persistArtifactsNode;
        this.memoryStoreClient = memoryStoreClient;
        this.sessionStreamService = sessionStreamService;
        this.supervisorAgentRoutingService = supervisorAgentRoutingService;
    }

    public SupervisorWorkflowState handoff(SupervisorWorkflowState state,
                                           String nextDomain,
                                           Map<String, Object> context,
                                           String handoffType) {
        RegisteredAgentDescriptor nextAgent = selectAgent(nextDomain, state.userMessage(), context);
        String nextIntent = resolveIntent(nextDomain, context);
        Map<String, Object> downstreamContext = sanitizeHandoffContext(context, state.userId());
        publish(state.sessionId(), state.taskId(), nextAgent.agentId(),
                "handoff.started", "Invoking next agent",
                Map.of("nextDomain", nextDomain, "nextIntent", nextIntent, "handoffType", handoffType), state.traceId());

        AgentTaskInvokeRequest handoffRequest = new AgentTaskInvokeRequest(
                        state.sessionId(),
                        state.taskId(),
                        null,
                        state.traceId(),
                        distributedAgentProperties.getSupervisorAgentId(),
                        nextAgent.agentId(),
                        nextIntent,
                        nextDomain,
                        state.userMessage(),
                        downstreamContext,
                        state.artifactIds(),
                        List.of(),
                        resolveExpectedOutput(nextDomain, nextIntent),
                        A2aInvokeSupport.buildIdempotencyKey(state.taskId(), nextAgent.agentId(), nextIntent, 0),
                        Boolean.TRUE.equals(downstreamContext.get("requestStream"))
                );
        AgentTaskInvokeResponse response = a2aExecutionService.execute(
                nextAgent,
                handoffRequest,
                "handoff",
                Map.of("nextDomain", nextDomain, "nextIntent", nextIntent, "handoffType", handoffType, "targetAgentId", nextAgent.agentId())
        );

        List<String> artifactIds = persistArtifactsNode.persistSingle(
                state.taskId(),
                state.sessionId(),
                nextAgent.agentId(),
                state.userId(),
                state.traceId(),
                response
        );
        memoryStoreClient.createHandoffRelation(new AgentHandoffPacket(
                state.sessionId(),
                state.taskId(),
                state.taskId(),
                state.traceId(),
                state.selectedAgentId(),
                nextAgent.agentId(),
                handoffType,
                state.userMessage(),
                downstreamContext,
                artifactIds,
                List.of("supervisor-managed"),
                resolveExpectedOutput(nextDomain, nextIntent)
        ));
        publish(state.sessionId(), state.taskId(), nextAgent.agentId(),
                "handoff.completed", "Next agent completed handoff",
                Map.of(
                        "nextDomain", nextDomain,
                        "nextIntent", nextIntent,
                        "handoffType", handoffType,
                        "status", response.status(),
                        "userId", state.userId() == null ? "" : state.userId()
                ),
                state.traceId());
        return new SupervisorWorkflowState(
                state.sessionId(),
                state.taskId(),
                state.traceId(),
                state.userId(),
                state.userMessage(),
                WorkflowStatus.RUNNING,
                nextAgent.agentId(),
                downstreamContext,
                appendHandoffHistory(state.handoffHistory(), state.selectedAgentId(), nextAgent.agentId(), handoffType, nextDomain, state.traceId()),
                artifactIds,
                response,
                null,
                state.latestApprovalDecision(),
                null
        );
    }

    private RegisteredAgentDescriptor selectAgent(String domain, String message, Map<String, Object> context) {
        return supervisorAgentRoutingService.selectAgent(domain, message, context);
    }

    private Map<String, Object> sanitizeHandoffContext(Map<String, Object> context, String userId) {
        Map<String, Object> sanitized = new java.util.LinkedHashMap<>();
        if (StringUtils.hasText(userId)) {
            sanitized.put("userId", userId);
        }
        copyIfPresent(context, sanitized, "domain");
        copyIfPresent(context, sanitized, "nextDomain");
        copyIfPresent(context, sanitized, "nextIntent");
        copyIfPresent(context, sanitized, "requestStream");
        copyIfPresent(context, sanitized, "forceAsyncA2a");
        copyIfPresent(context, sanitized, "grayRelease");
        copyIfPresent(context, sanitized, "grayStrategyVersion");
        copyIfPresent(context, sanitized, "preferredAgentIds");
        copyIfPresent(context, sanitized, "routeOverrideDomains");
        copyIfPresent(context, sanitized, "latestArtifactIds");
        copyIfPresent(context, sanitized, "latestPrimaryArtifactId");
        copyIfPresent(context, sanitized, "artifactRefs");
        copyIfPresent(context, sanitized, "copyDraftArtifactId");
        copyIfPresent(context, sanitized, "copyDraftBodyArtifactId");
        copyIfPresent(context, sanitized, "publishPayloadArtifactId");
        copyIfPresent(context, sanitized, "publishPayloadBodyArtifactId");
        copyIfPresent(context, sanitized, "mediaTaskArtifactId");
        copyIfPresent(context, sanitized, "mediaTaskDetailArtifactId");
        copyIfPresent(context, sanitized, "contractSummaryArtifactId");
        copyIfPresent(context, sanitized, "contractReviewDetailArtifactId");
        copyIfPresent(context, sanitized, "settlementSummaryArtifactId");
        copyIfPresent(context, sanitized, "settlementDetailArtifactId");
        copyIfPresent(context, sanitized, "listingOutput");
        copyIfPresent(context, sanitized, "tradeOutput");
        copyIfPresent(context, sanitized, "mergeSummary");
        copyIfPresent(context, sanitized, "routeDecision");
        copyIfPresent(context, sanitized, "listingCount");
        copyIfPresent(context, sanitized, "mediaTaskId");
        copyIfPresent(context, sanitized, "contentId");
        copyIfPresent(context, sanitized, "title");
        copyIfPresent(context, sanitized, "videoUrl");
        copyIfPresent(context, sanitized, "coverImageUrl");
        copyIfPresent(context, sanitized, "publishStatus");
        copyIfPresent(context, sanitized, "externalPublishId");
        copyIfPresent(context, sanitized, "notificationId");
        copyIfPresent(context, sanitized, "contractStatus");
        copyIfPresent(context, sanitized, "tradeDecision");
        copyIfPresent(context, sanitized, "settlementStatus");
        copyIfPresent(context, sanitized, "nextAction");
        mergeCompactContentContext(context, sanitized);
        return Map.copyOf(sanitized);
    }

    private void mergeCompactContentContext(Map<String, Object> source, Map<String, Object> target) {
        Object copywriting = source.get("copywriting");
        if (copywriting != null) {
            target.put("copywriting", copywriting);
            return;
        }
        Object draftText = source.get("draftText");
        if (draftText != null) {
            target.put("copywriting", draftText);
        }
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private List<Map<String, Object>> appendHandoffHistory(List<Map<String, Object>> current,
                                                           String fromAgent,
                                                           String toAgent,
                                                           String handoffType,
                                                           String domain,
                                                           String traceId) {
        List<Map<String, Object>> merged = new ArrayList<>(current == null ? List.of() : current);
        merged.add(Map.of(
                "fromAgent", fromAgent,
                "toAgent", toAgent,
                "handoffType", handoffType,
                "domain", domain,
                "traceId", traceId,
                "timestamp", System.currentTimeMillis()
        ));
        return List.copyOf(merged);
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

    private String resolveIntent(String domain, Map<String, Object> context) {
        if (context != null && context.get("nextIntent") != null && StringUtils.hasText(String.valueOf(context.get("nextIntent")))) {
            return String.valueOf(context.get("nextIntent"));
        }
        return switch (domain) {
            case "marketing" -> "marketing.generate_copy";
            case "media" -> "media.generate_video_task";
            case "trade" -> "trade.feasibility_analysis";
            case "contract" -> "contract.risk_review";
            case "notification" -> "notification.send";
            case "settlement" -> "settlement.prepare";
            default -> "listing.search";
        };
    }

    private String resolveExpectedOutput(String domain, String intent) {
        if ("marketing.publish_prepare".equalsIgnoreCase(intent)) {
            return "Return a publish preparation payload with compact structured output";
        }
        if ("marketing.publish".equalsIgnoreCase(intent)) {
            return "Return a publish execution result with compact structured output";
        }
        return switch (domain) {
            case "marketing" -> "Return a marketing copy draft with compact structured output";
            case "media" -> "Return a media generation task with compact structured output";
            case "trade" -> "Return a trade assessment with compact structured output";
            case "contract" -> "Return a contract summary and risk review with compact structured output";
            case "notification" -> "Return a notification delivery summary with compact structured output";
            case "settlement" -> "Return a settlement preparation summary with compact structured output";
            default -> "Return listing search summaries with compact structured output";
        };
    }

    private boolean containsListingIntent(String message) {
        return StringUtils.hasText(message) && (message.contains("房源")
                || message.contains("找房")
                || message.contains("小区")
                || message.contains("listing")
                || message.contains("房子"));
    }
}
