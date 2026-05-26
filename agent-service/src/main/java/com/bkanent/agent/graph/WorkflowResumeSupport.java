package com.bkanent.agent.graph;

import com.bkanent.agent.client.A2aAgentClient;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.graph.node.BuildNextStageApprovalNode;
import com.bkanent.agent.graph.node.MergeParallelResultNode;
import com.bkanent.agent.graph.node.ParallelInvokeNode;
import com.bkanent.agent.graph.node.PersistArtifactsNode;
import com.bkanent.agent.graph.node.PersistParallelArtifactsNode;
import com.bkanent.agent.graph.node.PersistSessionNode;
import com.bkanent.agent.graph.node.RouteDecisionNode;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.agent.workflow.GraphCheckpointStore;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.A2aInvokeSupport;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WorkflowResumeSupport {

    private final AgentRegistry agentRegistry;
    private final A2aAgentClient a2aAgentClient;
    private final DistributedAgentProperties distributedAgentProperties;
    private final ApprovalSubgraphService approvalSubgraphService;
    private final HandoffSubgraph handoffSubgraph;
    private final ParallelInvokeNode parallelInvokeNode;
    private final PersistParallelArtifactsNode persistParallelArtifactsNode;
    private final MergeParallelResultNode mergeParallelResultNode;
    private final PersistArtifactsNode persistArtifactsNode;
    private final RouteDecisionNode routeDecisionNode;
    private final BuildNextStageApprovalNode buildNextStageApprovalNode;
    private final PersistSessionNode persistSessionNode;
    private final GraphCheckpointStore checkpointStore;
    private final SessionStreamService sessionStreamService;

    public WorkflowResumeSupport(AgentRegistry agentRegistry,
                                 A2aAgentClient a2aAgentClient,
                                 DistributedAgentProperties distributedAgentProperties,
                                 ApprovalSubgraphService approvalSubgraphService,
                                 HandoffSubgraph handoffSubgraph,
                                 ParallelInvokeNode parallelInvokeNode,
                                 PersistParallelArtifactsNode persistParallelArtifactsNode,
                                 MergeParallelResultNode mergeParallelResultNode,
                                 PersistArtifactsNode persistArtifactsNode,
                                 RouteDecisionNode routeDecisionNode,
                                 BuildNextStageApprovalNode buildNextStageApprovalNode,
                                 PersistSessionNode persistSessionNode,
                                 GraphCheckpointStore checkpointStore,
                                 SessionStreamService sessionStreamService) {
        this.agentRegistry = agentRegistry;
        this.a2aAgentClient = a2aAgentClient;
        this.distributedAgentProperties = distributedAgentProperties;
        this.approvalSubgraphService = approvalSubgraphService;
        this.handoffSubgraph = handoffSubgraph;
        this.parallelInvokeNode = parallelInvokeNode;
        this.persistParallelArtifactsNode = persistParallelArtifactsNode;
        this.mergeParallelResultNode = mergeParallelResultNode;
        this.persistArtifactsNode = persistArtifactsNode;
        this.routeDecisionNode = routeDecisionNode;
        this.buildNextStageApprovalNode = buildNextStageApprovalNode;
        this.persistSessionNode = persistSessionNode;
        this.checkpointStore = checkpointStore;
        this.sessionStreamService = sessionStreamService;
    }

    public SupervisorTaskResponse resume(SupervisorWorkflowState state, String resumeAction, String feedback) {
        return switch (normalizeResumeAction(resumeAction)) {
            case "invoke-next-agent" -> continueWithNextAgent(state);
            case "route-after-parallel" -> routeAfterParallel(state);
            case "regenerate" -> regenerate(state, feedback);
            case "cancel" -> approvalSubgraphService.terminate(state, feedback);
            default -> complete(state, null, Map.of("status", WorkflowStatus.COMPLETED.name()));
        };
    }

    public SupervisorTaskResponse complete(SupervisorWorkflowState state,
                                           String answerOverride,
                                           Map<String, Object> metadata) {
        emitPublishEvents(state);
        NextHintRoute nextHintRoute = resolveNextHintRoute(state);
        if (nextHintRoute != null) {
            if (shouldHandleNotificationByEvent(state, nextHintRoute)) {
                publishNotificationRequested(state);
                return finalizeCompletion(state, answerOverride, metadata);
            }
            Map<String, Object> context = new LinkedHashMap<>(state.sharedContext());
            context.put("nextDomain", nextHintRoute.domain());
            context.put("nextIntent", nextHintRoute.intent());
            publish(state.sessionId(), state.taskId(), state.selectedAgentId(),
                    "task.next_hint_routed",
                    "Routing by next hint " + nextHintRoute.intent(),
                    Map.of("nextDomain", nextHintRoute.domain(), "nextIntent", nextHintRoute.intent()),
                    state.traceId());
            return continueWithNextAgent(state, nextHintRoute.domain(), context, "next_hint");
        }
        return finalizeCompletion(state, answerOverride, metadata);
    }

    private SupervisorTaskResponse finalizeCompletion(SupervisorWorkflowState state,
                                                      String answerOverride,
                                                      Map<String, Object> metadata) {
        String answer = StringUtils.hasText(answerOverride) ? answerOverride : buildFinalAnswer(state.latestAgentResponse());
        SupervisorWorkflowState completed = state.withWorkflowStatus(WorkflowStatus.COMPLETED).withFinalAnswer(answer);
        persistSessionNode.persist(completed, answer);
        checkpointStore.delete(completed.taskId());
        publish(completed.sessionId(), completed.taskId(), completed.selectedAgentId(),
                "task.completed", answer, completionMetadata(completed, metadata), completed.traceId());
        return buildResponse(completed, answer);
    }

    public SupervisorTaskResponse routeAfterParallel(SupervisorWorkflowState state) {
        RouteDecisionNode.RouteDecision routeDecision = routeDecisionNode.evaluate(state.sharedContext(), state.latestAgentResponse());
        Map<String, Object> context = new LinkedHashMap<>(state.sharedContext());
        context.put("routeDecision", routeDecision.asMap());
        publish(state.sessionId(), state.taskId(), state.selectedAgentId(),
                "task.route_decided", routeDecision.summary(), routeDecision.asMap(), state.traceId());

        if (!StringUtils.hasText(routeDecision.nextDomain())) {
            SupervisorWorkflowState routedState = new SupervisorWorkflowState(
                    state.sessionId(),
                    state.taskId(),
                    state.traceId(),
                    state.userId(),
                    state.userMessage(),
                    state.workflowStatus(),
                    state.selectedAgentId(),
                    Map.copyOf(context),
                    state.handoffHistory(),
                    state.artifactIds(),
                    state.latestAgentResponse(),
                    state.pendingApproval(),
                    state.latestApprovalDecision(),
                    routeDecision.summary()
            );
            return complete(routedState, routeDecision.summary(), Map.of("routeDecision", routeDecision.asMap()));
        }

        context.put("nextDomain", routeDecision.nextDomain());
        return continueWithNextAgent(state, routeDecision.nextDomain(), context, "route_decision");
    }

    public SupervisorTaskResponse regenerate(SupervisorWorkflowState state, String feedback) {
        ApprovalRequest pendingApproval = state.pendingApproval();
        if (pendingApproval == null) {
            throw new IllegalStateException("No pending approval found");
        }
        if (isParallelWorkflow(state)) {
            return regenerateParallel(state, feedback, pendingApproval);
        }
        return regenerateSingle(state, feedback, pendingApproval);
    }

    private SupervisorTaskResponse regenerateSingle(SupervisorWorkflowState state,
                                                    String feedback,
                                                    ApprovalRequest pendingApproval) {
        int nextRetry = pendingApproval.retryCount() == null ? 1 : pendingApproval.retryCount() + 1;
        if (pendingApproval.maxRetryCount() != null && nextRetry > pendingApproval.maxRetryCount()) {
            return approvalSubgraphService.failRetryLimit(state, nextRetry);
        }

        RegisteredAgentDescriptor descriptor = agentRegistry.getByAgentId(state.selectedAgentId())
                .orElseThrow(() -> new IllegalStateException("No agent descriptor for " + state.selectedAgentId()));
        Map<String, Object> context = new LinkedHashMap<>(state.sharedContext());
        context.putIfAbsent("userId", state.userId());
        context.put("approvalFeedback", feedback);
        context.put("retryCount", nextRetry);
        publish(state.sessionId(), state.taskId(), descriptor.agentId(),
                "task.regeneration_started", "Regenerating after rejection",
                Map.of("retryCount", nextRetry), state.traceId());

        String domain = resolveDomain(context, state.userMessage());
        String intent = resolveIntent(domain);
        AgentTaskInvokeResponse response = a2aAgentClient.invoke(
                descriptor,
                new AgentTaskInvokeRequest(
                        state.sessionId(),
                        state.taskId(),
                        null,
                        state.traceId(),
                        distributedAgentProperties.getSupervisorAgentId(),
                        state.selectedAgentId(),
                        intent,
                        domain,
                        state.userMessage(),
                        context,
                        state.artifactIds(),
                        List.of(),
                        resolveExpectedOutput(domain),
                        A2aInvokeSupport.buildIdempotencyKey(state.taskId(), state.selectedAgentId(), intent, nextRetry),
                        false
                )
        );

        List<String> artifactIds = persistArtifactsNode.persistSingle(
                state.taskId(),
                state.sessionId(),
                state.selectedAgentId(),
                state.userId(),
                state.traceId(),
                response
        );
        ApprovalRequest nextApproval = rebuildApproval(pendingApproval, response, nextRetry,
                "Content regenerated after rejection, please review again.");
        SupervisorWorkflowState regenerated = new SupervisorWorkflowState(
                state.sessionId(),
                state.taskId(),
                state.traceId(),
                state.userId(),
                state.userMessage(),
                WorkflowStatus.RUNNING,
                state.selectedAgentId(),
                Map.copyOf(context),
                state.handoffHistory(),
                artifactIds,
                response,
                null,
                state.latestApprovalDecision(),
                null
        );
        return approvalSubgraphService.enterWaitingState(
                regenerated,
                nextApproval,
                Map.of("approvalId", nextApproval.approvalId(), "retryCount", nextRetry)
        );
    }

    private SupervisorTaskResponse regenerateParallel(SupervisorWorkflowState state,
                                                      String feedback,
                                                      ApprovalRequest pendingApproval) {
        int nextRetry = pendingApproval.retryCount() == null ? 1 : pendingApproval.retryCount() + 1;
        if (pendingApproval.maxRetryCount() != null && nextRetry > pendingApproval.maxRetryCount()) {
            return approvalSubgraphService.failRetryLimit(state, nextRetry);
        }

        Map<String, Object> context = new LinkedHashMap<>(state.sharedContext());
        context.put("approvalFeedback", feedback);
        context.put("retryCount", nextRetry);
        List<String> parallelDomains = resolveParallelDomains(context, state.userMessage());
        SupervisorTaskRequest replayRequest = new SupervisorTaskRequest(
                state.sessionId(),
                state.userId(),
                state.taskId(),
                state.traceId(),
                state.userMessage(),
                context,
                null,
                false
        );
        SupervisorGraphState replayGraphState = SupervisorGraphState.initialize(
                state.sessionId(),
                state.taskId(),
                state.traceId(),
                state.userId(),
                state.userMessage()
        ).withSharedContext(Map.copyOf(context))
                .withIntent("parallel.replay",
                        state.sharedContext().get("domain") == null ? "listing" : String.valueOf(state.sharedContext().get("domain")),
                        "parallel")
                .withPlan(true, true, parallelDomains)
                .withSelectedAgent(state.selectedAgentId());

        AgentTaskInvokeResponse parallelResponse = parallelInvokeNode.invoke(replayRequest, replayGraphState);
        List<String> artifactIds = persistParallelArtifactsNode.persist(
                state.taskId(),
                state.sessionId(),
                state.userId(),
                state.traceId(),
                parallelDomains,
                parallelResponse
        );
        ApprovalRequest nextApproval = rebuildApproval(pendingApproval, parallelResponse, nextRetry,
                "Parallel result regenerated after rejection, please review again.");
        SupervisorWorkflowState regenerated = mergeParallelResultNode.merge(
                replayGraphState.withSharedContext(Map.copyOf(context)),
                artifactIds,
                parallelResponse
        );
        return approvalSubgraphService.enterWaitingState(
                regenerated.withPendingApproval(null).withWorkflowStatus(WorkflowStatus.RUNNING),
                nextApproval,
                Map.of("approvalId", nextApproval.approvalId(), "retryCount", nextRetry)
        );
    }

    public SupervisorTaskResponse continueWithNextAgent(SupervisorWorkflowState state) {
        String nextDomain = resolveNextDomain(state.sharedContext());
        return continueWithNextAgent(state, nextDomain, new LinkedHashMap<>(state.sharedContext()), "next_agent");
    }

    public SupervisorTaskResponse continueWithNextAgent(SupervisorWorkflowState state,
                                                        String nextDomain,
                                                        Map<String, Object> context,
                                                        String handoffType) {
        if (shouldHandleNotificationByEvent(nextDomain, state, context)) {
            publishNotificationRequested(state, context);
            return finalizeCompletion(state, null, Map.of(
                    "status", WorkflowStatus.COMPLETED.name(),
                    "notificationMode", "event_first"
            ));
        }
        SupervisorWorkflowState nextState = handoffSubgraph.execute(state, nextDomain, context, handoffType);
        if (requireNextApproval(nextState.sharedContext())) {
            ApprovalRequest approvalRequest = buildNextStageApprovalNode.build(nextState);
            return approvalSubgraphService.enterWaitingState(
                    nextState,
                    approvalRequest,
                    Map.of("approvalId", approvalRequest.approvalId(), "approvalType", approvalRequest.approvalType())
            );
        }
        return complete(nextState, null, Map.of("status", WorkflowStatus.COMPLETED.name()));
    }

    private ApprovalRequest rebuildApproval(ApprovalRequest pendingApproval,
                                            AgentTaskInvokeResponse response,
                                            int nextRetry,
                                            String summary) {
        return new ApprovalRequest(
                pendingApproval.approvalId(),
                pendingApproval.taskId(),
                pendingApproval.sessionId(),
                pendingApproval.approvalType(),
                pendingApproval.subjectType(),
                pendingApproval.subjectId(),
                pendingApproval.subjectVersion() == null ? 2 : pendingApproval.subjectVersion() + 1,
                pendingApproval.title(),
                summary,
                response.structuredOutput(),
                pendingApproval.approveNextNode(),
                pendingApproval.rejectNextNode(),
                pendingApproval.terminateNextNode(),
                nextRetry,
                pendingApproval.maxRetryCount(),
                pendingApproval.traceId()
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
                metadata == null ? Map.of() : metadata,
                traceId,
                System.currentTimeMillis()
        ));
    }

    private Map<String, Object> withUserId(Map<String, Object> metadata, String userId) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        merged.put("userId", userId == null ? "" : userId);
        merged.putIfAbsent("stage", "workflow");
        return merged;
    }

    private Map<String, Object> completionMetadata(SupervisorWorkflowState state, Map<String, Object> metadata) {
        Map<String, Object> merged = new LinkedHashMap<>(withUserId(metadata, state.userId()));
        merged.put("artifactCount", state.artifactIds() == null ? 0 : state.artifactIds().size());
        if (state.artifactIds() != null && !state.artifactIds().isEmpty()) {
            merged.put("artifactId", state.artifactIds().get(0));
        }
        if (state.pendingApproval() != null && StringUtils.hasText(state.pendingApproval().approvalId())) {
            merged.put("approvalId", state.pendingApproval().approvalId());
        }
        return Map.copyOf(merged);
    }

    private boolean shouldHandleNotificationByEvent(SupervisorWorkflowState state, NextHintRoute nextHintRoute) {
        return nextHintRoute != null && shouldHandleNotificationByEvent(nextHintRoute.domain(), state, state.sharedContext());
    }

    private boolean shouldHandleNotificationByEvent(String nextDomain,
                                                    SupervisorWorkflowState state,
                                                    Map<String, Object> context) {
        return "notification".equals(nextDomain)
                && !"notification".equals(state.selectedAgentId())
                && !Boolean.TRUE.equals((context == null ? Map.of() : context).get("forceNotificationHandoff"));
    }

    private void publishNotificationRequested(SupervisorWorkflowState state) {
        publishNotificationRequested(state, state.sharedContext());
    }

    private void publishNotificationRequested(SupervisorWorkflowState state, Map<String, Object> context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", state.userId() == null ? "" : state.userId());
        metadata.put("contentId", valueFromResponse(state, "contentId"));
        metadata.put("publishStatus", valueFromResponse(state, "publishStatus"));
        metadata.put("externalPublishId", valueFromResponse(state, "externalPublishId"));
        String nextIntent = context == null ? null : String.valueOf(context.getOrDefault("nextIntent", ""));
        if (StringUtils.hasText(nextIntent)) {
            metadata.put("nextIntent", nextIntent);
        }
        publish(state.sessionId(), state.taskId(), state.selectedAgentId(),
                "notification.requested",
                "Notification will be handled by event consumer",
                metadata,
                state.traceId());
    }

    private void emitPublishEvents(SupervisorWorkflowState state) {
        AgentTaskInvokeResponse response = state.latestAgentResponse();
        if (response == null || response.structuredOutput() == null) {
            return;
        }
        String contentType = String.valueOf(response.structuredOutput().getOrDefault("contentType", ""));
        if ("publish_payload".equalsIgnoreCase(contentType)) {
            publish(state.sessionId(), state.taskId(), state.selectedAgentId(),
                    "publish.prepared",
                    "Publish payload prepared",
                    Map.of(
                            "userId", state.userId() == null ? "" : state.userId(),
                            "contentId", valueFromResponse(state, "contentId"),
                            "publishStatus", valueFromResponse(state, "publishStatus")
                    ),
                    state.traceId());
            return;
        }
        if ("publish_result".equalsIgnoreCase(contentType)) {
            publish(state.sessionId(), state.taskId(), state.selectedAgentId(),
                    "publish.completed",
                    "Content published",
                    Map.of(
                            "userId", state.userId() == null ? "" : state.userId(),
                            "contentId", valueFromResponse(state, "contentId"),
                            "publishStatus", valueFromResponse(state, "publishStatus"),
                            "externalPublishId", valueFromResponse(state, "externalPublishId")
                    ),
                    state.traceId());
        }
    }

    private String valueFromResponse(SupervisorWorkflowState state, String key) {
        if (state.latestAgentResponse() == null || state.latestAgentResponse().structuredOutput() == null) {
            return "";
        }
        Object value = state.latestAgentResponse().structuredOutput().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private SupervisorTaskResponse buildResponse(SupervisorWorkflowState state, String answer) {
        return new SupervisorTaskResponse(
                state.sessionId(),
                state.taskId(),
                state.workflowStatus().name(),
                answer,
                state.artifactIds(),
                state.traceId(),
                state.selectedAgentId(),
                Map.of()
        );
    }

    private String buildFinalAnswer(AgentTaskInvokeResponse response) {
        if (response == null) {
            return "Workflow finished.";
        }
        if (StringUtils.hasText(response.summary())) {
            return response.summary();
        }
        Object count = response.structuredOutput() == null ? null : response.structuredOutput().get("listingCount");
        if (count != null) {
            return "Found " + count + " listing candidates.";
        }
        return "Child agent finished processing.";
    }

    private boolean isParallelWorkflow(SupervisorWorkflowState state) {
        return state.selectedAgentId() != null && state.selectedAgentId().startsWith("parallel:");
    }

    private String resolveNextDomain(Map<String, Object> context) {
        if (context == null) {
            return "media";
        }
        Object domain = context.get("nextDomain");
        return domain == null ? "media" : String.valueOf(domain);
    }

    private boolean requireNextApproval(Map<String, Object> context) {
        return context != null && Boolean.TRUE.equals(context.get("nextRequireApproval"));
    }

    private List<String> resolveParallelDomains(Map<String, Object> context, String message) {
        if (context != null && context.get("parallelDomains") instanceof Collection<?> collection) {
            List<String> domains = collection.stream()
                    .map(String::valueOf)
                    .filter(StringUtils::hasText)
                    .toList();
            if (domains.size() > 1) {
                return domains;
            }
        }
        if (context != null && Boolean.TRUE.equals(context.get("requireParallel"))
                && containsListingIntent(message) && containsTradeIntent(message)) {
            return List.of("listing", "trade");
        }
        return List.of();
    }

    private String resolveDomain(Map<String, Object> context, String message) {
        if (context != null && StringUtils.hasText(String.valueOf(context.get("domain")))) {
            return String.valueOf(context.get("domain"));
        }
        if (containsContractIntent(message)) {
            return "contract";
        }
        if (containsNotificationIntent(message)) {
            return "notification";
        }
        if (containsSettlementIntent(message)) {
            return "settlement";
        }
        if (containsMarketingIntent(message)) {
            return "marketing";
        }
        if (containsTradeIntent(message)) {
            return "trade";
        }
        return "listing";
    }

    private String resolveIntent(String domain) {
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

    private String resolveExpectedOutput(String domain) {
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

    private String normalizeResumeAction(String resumeAction) {
        return StringUtils.hasText(resumeAction) ? resumeAction : "complete";
    }

    private NextHintRoute resolveNextHintRoute(SupervisorWorkflowState state) {
        AgentTaskInvokeResponse response = state.latestAgentResponse();
        if (response == null || response.nextHints() == null || response.nextHints().isEmpty()) {
            return null;
        }
        for (String nextHint : response.nextHints()) {
            NextHintRoute route = mapNextHint(nextHint);
            if (route != null) {
                return route;
            }
        }
        return null;
    }

    private NextHintRoute mapNextHint(String nextHint) {
        if (!StringUtils.hasText(nextHint)) {
            return null;
        }
        return switch (nextHint) {
            case "marketing.publish_prepare" -> new NextHintRoute("marketing", nextHint);
            case "marketing.publish" -> new NextHintRoute("marketing", nextHint);
            case "notification.send" -> new NextHintRoute("notification", nextHint);
            case "settlement.prepare", "settlement.batch" -> new NextHintRoute("settlement", "settlement.prepare");
            default -> null;
        };
    }

    private boolean containsListingIntent(String message) {
        return StringUtils.hasText(message) && (message.contains("房源")
                || message.contains("找房")
                || message.contains("小区")
                || message.contains("listing")
                || message.contains("房子"));
    }

    private boolean containsMarketingIntent(String message) {
        return StringUtils.hasText(message) && (message.contains("文案")
                || message.contains("营销")
                || message.contains("广告")
                || message.contains("推广")
                || message.contains("小红书")
                || message.contains("抖音"));
    }

    private boolean containsTradeIntent(String message) {
        return StringUtils.hasText(message) && (message.contains("交易")
                || message.contains("成交")
                || message.contains("风险")
                || message.contains("可行性")
                || message.contains("trade"));
    }

    private boolean containsContractIntent(String message) {
        return StringUtils.hasText(message) && (message.contains("合同")
                || message.contains("签约")
                || message.contains("归档")
                || message.contains("ocr")
                || message.contains("OCR")
                || message.contains("contract"));
    }

    private boolean containsSettlementIntent(String message) {
        return StringUtils.hasText(message) && (message.contains("结算")
                || message.contains("佣金")
                || message.contains("出款")
                || message.contains("打款")
                || message.contains("settlement"));
    }

    private boolean containsNotificationIntent(String message) {
        return StringUtils.hasText(message) && (message.contains("通知")
                || message.contains("提醒")
                || message.contains("消息")
                || message.contains("notification"));
    }

    private record NextHintRoute(String domain, String intent) {
    }
}
