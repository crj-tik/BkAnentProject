package com.bkanent.agent.service;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.graph.ApprovalSubgraphService;
import com.bkanent.agent.graph.CompletionSubgraph;
import com.bkanent.agent.graph.ParallelAgentSubgraph;
import com.bkanent.agent.graph.ResumeSubgraph;
import com.bkanent.agent.graph.RouteDecisionSubgraph;
import com.bkanent.agent.graph.SingleAgentSubgraph;
import com.bkanent.agent.graph.SupervisorGraphPlanner;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.graph.node.BuildApprovalRequestNode;
import com.bkanent.agent.graph.node.RouteDecisionNode;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.agent.workflow.GraphCheckpointStore;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.ApprovalCallbackRequest;
import com.bkanent.common.agent.ApprovalDecision;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SupervisorWorkflowService implements ApprovalCommandService {

    private final AgentRegistry agentRegistry;
    private final DistributedAgentProperties distributedAgentProperties;
    private final ApprovalSubgraphService approvalSubgraphService;
    private final CompletionSubgraph completionSubgraph;
    private final SingleAgentSubgraph singleAgentSubgraph;
    private final ParallelAgentSubgraph parallelAgentSubgraph;
    private final RouteDecisionSubgraph routeDecisionSubgraph;
    private final ResumeSubgraph resumeSubgraph;
    private final SupervisorGraphPlanner supervisorGraphPlanner;
    private final RouteDecisionNode routeDecisionNode;
    private final BuildApprovalRequestNode buildApprovalRequestNode;
    private final GraphCheckpointStore checkpointStore;
    private final SessionStreamService sessionStreamService;
    private final SupervisorGovernanceService supervisorGovernanceService;

    public SupervisorWorkflowService(AgentRegistry agentRegistry,
                                     DistributedAgentProperties distributedAgentProperties,
                                     ApprovalSubgraphService approvalSubgraphService,
                                     CompletionSubgraph completionSubgraph,
                                     SingleAgentSubgraph singleAgentSubgraph,
                                     ParallelAgentSubgraph parallelAgentSubgraph,
                                     RouteDecisionSubgraph routeDecisionSubgraph,
                                     ResumeSubgraph resumeSubgraph,
                                     SupervisorGraphPlanner supervisorGraphPlanner,
                                     RouteDecisionNode routeDecisionNode,
                                     BuildApprovalRequestNode buildApprovalRequestNode,
                                     GraphCheckpointStore checkpointStore,
                                     SessionStreamService sessionStreamService,
                                     SupervisorGovernanceService supervisorGovernanceService) {
        this.agentRegistry = agentRegistry;
        this.distributedAgentProperties = distributedAgentProperties;
        this.approvalSubgraphService = approvalSubgraphService;
        this.completionSubgraph = completionSubgraph;
        this.singleAgentSubgraph = singleAgentSubgraph;
        this.parallelAgentSubgraph = parallelAgentSubgraph;
        this.routeDecisionSubgraph = routeDecisionSubgraph;
        this.resumeSubgraph = resumeSubgraph;
        this.supervisorGraphPlanner = supervisorGraphPlanner;
        this.routeDecisionNode = routeDecisionNode;
        this.buildApprovalRequestNode = buildApprovalRequestNode;
        this.checkpointStore = checkpointStore;
        this.sessionStreamService = sessionStreamService;
        this.supervisorGovernanceService = supervisorGovernanceService;
    }

    public SupervisorTaskResponse startWorkflow(SupervisorTaskRequest request) {
        String message = request.userMessage() == null ? "" : request.userMessage().trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }

        String sessionId = StringUtils.hasText(request.sessionId()) ? request.sessionId() : UUID.randomUUID().toString();
        String taskId = StringUtils.hasText(request.requestId()) ? request.requestId() : UUID.randomUUID().toString();
        String traceId = StringUtils.hasText(request.traceId()) ? request.traceId() : taskId;
        SupervisorGraphState graphState = supervisorGraphPlanner.plan(request, sessionId, taskId, traceId);
        Map<String, Object> sharedContext = graphState.sharedContext();

        emitEvent(sessionId, taskId, distributedAgentProperties.getSupervisorAgentId(),
                "task.started", "Supervisor workflow started",
                withGovernanceMetadata(Map.of("userId", request.userId()), sharedContext), traceId);

        List<String> parallelDomains = graphState.parallelDomains();
        if (parallelDomains.size() > 1) {
            return startParallelWorkflow(request, graphState);
        }

        String domain = graphState.domain();
        RegisteredAgentDescriptor descriptor = agentRegistry.getByAgentId(graphState.selectedAgentId())
                .orElseGet(() -> selectAgent(domain, message));
        emitEvent(sessionId, taskId, descriptor.agentId(),
                "handoff.started", "Invoking child agent", Map.of("domain", domain), traceId);

        SingleAgentSubgraph.ExecutionResult execution = singleAgentSubgraph.execute(request, graphState, descriptor);
        AgentTaskInvokeResponse response = execution.response();
        emitEvent(sessionId, taskId, descriptor.agentId(),
                "handoff.completed", "Child agent returned",
                Map.of("status", response.status(), "userId", request.userId() == null ? "" : request.userId()), traceId);
        SupervisorWorkflowState baseState = execution.workflowState();

        if (requireApproval(sharedContext)) {
            ApprovalRequest approvalRequest = buildApprovalRequestNode.build(baseState, sharedContext);
            return approvalSubgraphService.enterWaitingState(
                    baseState,
                    approvalRequest,
                    Map.of("approvalId", approvalRequest.approvalId(), "approvalType", approvalRequest.approvalType())
            );
        }

        return completionSubgraph.execute(baseState);
    }

    @Override
    public SupervisorTaskResponse handleCallback(ApprovalCallbackRequest request) {
        SupervisorWorkflowState state = checkpointStore.load(request.taskId())
                .orElseThrow(() -> new IllegalArgumentException("No workflow checkpoint for taskId=" + request.taskId()));
        ApprovalDecision decision = approvalSubgraphService.receiveDecision(state, request);
        ApprovalSubgraphService.DecisionTransition transition =
                approvalSubgraphService.applyDecisionTransition(state, decision);
        return switch (request.status()) {
            case APPROVED, REJECTED -> resumeSubgraph.execute(transition.state(), transition.resumeAction(), request.feedback());
            case TERMINATED -> approvalSubgraphService.terminate(transition.state(), request.feedback());
            case PENDING -> throw new IllegalArgumentException("PENDING is not a valid callback result");
        };
    }

    private SupervisorTaskResponse startParallelWorkflow(SupervisorTaskRequest request,
                                                         SupervisorGraphState graphState) {
        ParallelAgentSubgraph.ExecutionResult execution = parallelAgentSubgraph.execute(request, graphState);
        SupervisorWorkflowState parallelState = execution.workflowState();
        if (requireApproval(graphState.sharedContext())) {
            ApprovalRequest approvalRequest = buildApprovalRequest(parallelState, parallelState.sharedContext());
            return approvalSubgraphService.enterWaitingState(
                    parallelState,
                    approvalRequest,
                    Map.of("approvalId", approvalRequest.approvalId(), "parallelDomains", graphState.parallelDomains())
            );
        }
        if (shouldAutoRouteAfterParallel(parallelState)) {
            return routeDecisionSubgraph.execute(parallelState);
        }
        return completionSubgraph.execute(parallelState);
    }

    private RegisteredAgentDescriptor selectAgent(String domain, String message) {
        List<RegisteredAgentDescriptor> matchedAgents = agentRegistry.findByDomain(domain);
        if (!matchedAgents.isEmpty()) {
            return matchedAgents.get(0);
        }
        List<RegisteredAgentDescriptor> listingAgents = agentRegistry.findByDomain("listing");
        if (!listingAgents.isEmpty() && containsListingIntent(message)) {
            return listingAgents.get(0);
        }
        return agentRegistry.getByAgentId("listing-agent")
                .or(() -> listingAgents.stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No registered agent available"));
    }

    private ApprovalRequest buildApprovalRequest(SupervisorWorkflowState state, Map<String, Object> context) {
        String summary = textOrDefault(context.get("approvalSummary"), "Task result generated, waiting for approval.");
        String approvalType = textOrDefault(context.get("approvalType"), "generic-review");
        String subjectType = textOrDefault(context.get("subjectType"), "agent-output");
        int maxRetryCount = resolveInt(context, "maxRetryCount", 3);
        String approveNextNode = textOrDefault(context.get("approveNextNode"), defaultApproveNextNode(state));
        return new ApprovalRequest(
                UUID.randomUUID().toString(),
                state.taskId(),
                state.sessionId(),
                approvalType,
                subjectType,
                state.taskId(),
                1,
                "Approval Required",
                summary,
                state.latestAgentResponse() == null ? Map.of() : state.latestAgentResponse().structuredOutput(),
                approveNextNode,
                "regenerate",
                "cancel",
                0,
                maxRetryCount,
                state.traceId()
        );
    }

    private boolean requireApproval(Map<String, Object> context) {
        return context != null && Boolean.TRUE.equals(context.get("requireApproval"));
    }

    private boolean containsListingIntent(String message) {
        return message.contains("房源")
                || message.contains("找房")
                || message.contains("小区")
                || message.contains("listing")
                || message.contains("房子");
    }

    private String defaultApproveNextNode(SupervisorWorkflowState state) {
        return shouldAutoRouteAfterParallel(state) ? "route-after-parallel" : "complete";
    }

    private boolean shouldAutoRouteAfterParallel(SupervisorWorkflowState state) {
        return isParallelWorkflow(state) && routeDecisionNode.shouldAutoRoute(state.sharedContext(), state.latestAgentResponse());
    }

    private boolean isParallelWorkflow(SupervisorWorkflowState state) {
        return state.selectedAgentId() != null && state.selectedAgentId().startsWith("parallel:");
    }

    private int resolveInt(Map<String, Object> context, String key, int defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        Object value = context.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private void emitEvent(String sessionId,
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

    private Map<String, Object> withGovernanceMetadata(Map<String, Object> metadata, Map<String, Object> context) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        merged.putAll(supervisorGovernanceService.extractGovernanceMetadata(context));
        return Map.copyOf(merged);
    }

    private String textOrDefault(Object value, String defaultValue) {
        return StringUtils.hasText(value == null ? null : String.valueOf(value))
                ? String.valueOf(value)
                : defaultValue;
    }
}
