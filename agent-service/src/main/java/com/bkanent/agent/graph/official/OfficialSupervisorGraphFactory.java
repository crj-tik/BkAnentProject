package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncMultiCommandAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.MultiCommand;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.bkanent.agent.graph.SingleAgentSubgraph;
import com.bkanent.agent.graph.SupervisorGraphPlanner;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.graph.CompletionSubgraph;
import com.bkanent.agent.graph.node.BuildApprovalRequestNode;
import com.bkanent.agent.graph.node.BuildNextAgentContextNode;
import com.bkanent.agent.graph.node.HandoffNode;
import com.bkanent.agent.graph.node.MergeParallelResultNode;
import com.bkanent.agent.graph.node.ParallelInvokeNode;
import com.bkanent.agent.graph.node.PersistParallelArtifactsNode;
import com.bkanent.agent.graph.node.RouteDecisionNode;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.ApprovalDecision;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.agent.ApprovalStatus;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.agent.WorkflowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The single top-level Supervisor workflow. Domain services are invoked by
 * graph nodes, while all business transitions are represented by graph edges.
 */
@Component
public class OfficialSupervisorGraphFactory {

    private static final Map<String, String> PARALLEL_DOMAIN_NODES = Map.of(
            "listing", OfficialSupervisorGraphNodeNames.PARALLEL_LISTING,
            "marketing", OfficialSupervisorGraphNodeNames.PARALLEL_MARKETING,
            "media", OfficialSupervisorGraphNodeNames.PARALLEL_MEDIA,
            "trade", OfficialSupervisorGraphNodeNames.PARALLEL_TRADE,
            "contract", OfficialSupervisorGraphNodeNames.PARALLEL_CONTRACT,
            "settlement", OfficialSupervisorGraphNodeNames.PARALLEL_SETTLEMENT,
            "notification", OfficialSupervisorGraphNodeNames.PARALLEL_NOTIFICATION
    );

    private final OfficialSupervisorGraphSchema graphSchema;
    private final DatabaseCheckpointSaverFactory checkpointSaverFactory;
    private final SupervisorGraphPlanner supervisorGraphPlanner;
    private final SingleAgentSubgraph singleAgentSubgraph;
    private final CompletionSubgraph completionSubgraph;
    private final BuildApprovalRequestNode buildApprovalRequestNode;
    private final ParallelInvokeNode parallelInvokeNode;
    private final MergeParallelResultNode mergeParallelResultNode;
    private final PersistParallelArtifactsNode persistParallelArtifactsNode;
    private final AgentRegistry agentRegistry;
    private final ObjectMapper objectMapper;
    private final SessionStreamService sessionStreamService;
    private final BuildNextAgentContextNode buildNextAgentContextNode;
    private final HandoffNode handoffNode;
    private final RouteDecisionNode routeDecisionNode;

    public OfficialSupervisorGraphFactory(OfficialSupervisorGraphSchema graphSchema,
                                          DatabaseCheckpointSaverFactory checkpointSaverFactory,
                                          SupervisorGraphPlanner supervisorGraphPlanner,
                                          SingleAgentSubgraph singleAgentSubgraph,
                                          CompletionSubgraph completionSubgraph,
                                          BuildApprovalRequestNode buildApprovalRequestNode,
                                          ParallelInvokeNode parallelInvokeNode,
                                          MergeParallelResultNode mergeParallelResultNode,
                                          PersistParallelArtifactsNode persistParallelArtifactsNode,
                                          AgentRegistry agentRegistry,
                                          ObjectMapper objectMapper,
                                          SessionStreamService sessionStreamService,
                                          BuildNextAgentContextNode buildNextAgentContextNode,
                                          HandoffNode handoffNode,
                                          RouteDecisionNode routeDecisionNode) {
        this.graphSchema = graphSchema;
        this.checkpointSaverFactory = checkpointSaverFactory;
        this.supervisorGraphPlanner = supervisorGraphPlanner;
        this.singleAgentSubgraph = singleAgentSubgraph;
        this.completionSubgraph = completionSubgraph;
        this.buildApprovalRequestNode = buildApprovalRequestNode;
        this.parallelInvokeNode = parallelInvokeNode;
        this.mergeParallelResultNode = mergeParallelResultNode;
        this.persistParallelArtifactsNode = persistParallelArtifactsNode;
        this.agentRegistry = agentRegistry;
        this.objectMapper = objectMapper;
        this.sessionStreamService = sessionStreamService;
        this.buildNextAgentContextNode = buildNextAgentContextNode;
        this.handoffNode = handoffNode;
        this.routeDecisionNode = routeDecisionNode;
    }

    public CompiledGraph create() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(graphSchema.keyStrategyFactory().apply())
                .build();
        StateGraph stateGraph = new StateGraph("official-supervisor", keyStrategyFactory);
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PLAN, plan());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.ROUTE, route());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.APPROVAL_GATE, approvalGate());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.RESUME_DECISION, resumeDecision());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.SINGLE_AGENT, singleAgent());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.ROUTE_AFTER_EXECUTION, routeAfterExecution());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.HANDOFF, handoff());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PARALLEL_FAN_OUT, parallelFanOut());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PARALLEL_LISTING,
                parallelBranch("listing", OfficialSupervisorGraphNodeNames.PARALLEL_LISTING));
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PARALLEL_MARKETING,
                parallelBranch("marketing", OfficialSupervisorGraphNodeNames.PARALLEL_MARKETING));
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PARALLEL_MEDIA,
                parallelBranch("media", OfficialSupervisorGraphNodeNames.PARALLEL_MEDIA));
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PARALLEL_TRADE,
                parallelBranch("trade", OfficialSupervisorGraphNodeNames.PARALLEL_TRADE));
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PARALLEL_CONTRACT,
                parallelBranch("contract", OfficialSupervisorGraphNodeNames.PARALLEL_CONTRACT));
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PARALLEL_SETTLEMENT,
                parallelBranch("settlement", OfficialSupervisorGraphNodeNames.PARALLEL_SETTLEMENT));
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PARALLEL_NOTIFICATION,
                parallelBranch("notification", OfficialSupervisorGraphNodeNames.PARALLEL_NOTIFICATION));
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PARALLEL_AGGREGATE, parallelAggregate());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.REGENERATE, regenerate());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.COMPLETE, complete());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.COMPLETE_END, completeEnd());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.CANCEL, cancel());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.FAIL, fail());

        stateGraph.addEdge(StateGraph.START, OfficialSupervisorGraphNodeNames.PLAN);
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.PLAN, OfficialSupervisorGraphNodeNames.ROUTE);
        stateGraph.addConditionalEdges(
                OfficialSupervisorGraphNodeNames.ROUTE,
                AsyncEdgeAction.edge_async(this::routeTarget),
                Map.of(
                        "approval", OfficialSupervisorGraphNodeNames.APPROVAL_GATE,
                        "single", OfficialSupervisorGraphNodeNames.SINGLE_AGENT,
                        "parallel", OfficialSupervisorGraphNodeNames.PARALLEL_FAN_OUT,
                        "fail", OfficialSupervisorGraphNodeNames.FAIL
                )
        );
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.APPROVAL_GATE,
                OfficialSupervisorGraphNodeNames.RESUME_DECISION);
        stateGraph.addConditionalEdges(
                OfficialSupervisorGraphNodeNames.RESUME_DECISION,
                AsyncEdgeAction.edge_async(this::resumeTarget),
                Map.of(
                        "single", OfficialSupervisorGraphNodeNames.SINGLE_AGENT,
                        "parallel", OfficialSupervisorGraphNodeNames.PARALLEL_FAN_OUT,
                        "regenerate", OfficialSupervisorGraphNodeNames.REGENERATE,
                        "cancel", OfficialSupervisorGraphNodeNames.CANCEL,
                        "fail", OfficialSupervisorGraphNodeNames.FAIL
                )
        );
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.REGENERATE, OfficialSupervisorGraphNodeNames.PLAN);
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.SINGLE_AGENT,
                OfficialSupervisorGraphNodeNames.ROUTE_AFTER_EXECUTION);
        stateGraph.addParallelConditionalEdges(
                OfficialSupervisorGraphNodeNames.PARALLEL_FAN_OUT,
                AsyncMultiCommandAction.node_async(this::parallelTargets),
                parallelEdgeMappings()
        );
        stateGraph.addEdge(List.of(
                OfficialSupervisorGraphNodeNames.PARALLEL_LISTING,
                OfficialSupervisorGraphNodeNames.PARALLEL_MARKETING,
                OfficialSupervisorGraphNodeNames.PARALLEL_MEDIA,
                OfficialSupervisorGraphNodeNames.PARALLEL_TRADE,
                OfficialSupervisorGraphNodeNames.PARALLEL_CONTRACT,
                OfficialSupervisorGraphNodeNames.PARALLEL_SETTLEMENT,
                OfficialSupervisorGraphNodeNames.PARALLEL_NOTIFICATION
        ), OfficialSupervisorGraphNodeNames.PARALLEL_AGGREGATE);
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.PARALLEL_AGGREGATE,
                OfficialSupervisorGraphNodeNames.ROUTE_AFTER_EXECUTION);
        stateGraph.addConditionalEdges(
                OfficialSupervisorGraphNodeNames.ROUTE_AFTER_EXECUTION,
                AsyncEdgeAction.edge_async(this::executionTarget),
                Map.of(
                        "handoff", OfficialSupervisorGraphNodeNames.HANDOFF,
                        "complete", OfficialSupervisorGraphNodeNames.COMPLETE,
                        "fail", OfficialSupervisorGraphNodeNames.FAIL
                )
        );
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.HANDOFF,
                OfficialSupervisorGraphNodeNames.ROUTE_AFTER_EXECUTION);
        stateGraph.addConditionalEdges(
                OfficialSupervisorGraphNodeNames.COMPLETE,
                AsyncEdgeAction.edge_async(state -> StringUtils.hasText(
                        state.value(OfficialSupervisorGraphKeys.ERROR_CODE, (String) null))
                        ? "fail" : "end"),
                Map.of("end", OfficialSupervisorGraphNodeNames.COMPLETE_END,
                        "fail", OfficialSupervisorGraphNodeNames.FAIL)
        );
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.COMPLETE_END, StateGraph.END);
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.CANCEL, StateGraph.END);
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.FAIL, StateGraph.END);

        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(
                        checkpointSaverFactory.create("official-supervisor")).build())
                .interruptAfter(OfficialSupervisorGraphNodeNames.APPROVAL_GATE)
                .build());
    }

    private AsyncNodeAction plan() {
        NodeAction action = state -> {
            try {
                SupervisorTaskRequest request = requestOf(state);
                SupervisorGraphState planned = supervisorGraphPlanner.plan(
                        request,
                        request.sessionId(),
                        request.requestId(),
                        request.traceId()
                );
                SupervisorGraphState previous = OfficialGraphStateAdapters.toSupervisorGraphState(state);
                Map<String, Object> updates = new LinkedHashMap<>(
                        OfficialGraphStateAdapters.toDeltaMap(previous, planned));
                updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.ROUTE);
                updates.put(OfficialSupervisorGraphKeys.ERROR_CODE, null);
                updates.put(OfficialSupervisorGraphKeys.ERROR_MESSAGE, null);
                return updates;
            } catch (Exception exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                return error("PLAN_FAILED", message);
            }
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction route() {
        NodeAction action = state -> Map.of(
                OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.ROUTE
        );
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction approvalGate() {
        NodeAction action = state -> {
            SupervisorGraphState graphState = OfficialGraphStateAdapters.toSupervisorGraphState(state);
            if (graphState.parallelDomains().size() > 1
                    && !validParallelDomains(graphState.parallelDomains())) {
                return error("INVALID_PARALLEL_PLAN",
                        "parallelDomains must contain distinct supported domains");
            }
            SupervisorWorkflowState workflowState = OfficialGraphStateAdapters.toWorkflowState(state, objectMapper);
            ApprovalRequest base = buildApprovalRequestNode.build(workflowState, graphState.sharedContext());
            if (base == null) {
                return error("APPROVAL_REQUEST_FAILED", "Approval request builder returned no request");
            }
            String nextNode = graphState.parallelDomains().size() > 1
                    ? OfficialSupervisorGraphNodeNames.PARALLEL_FAN_OUT
                    : OfficialSupervisorGraphNodeNames.SINGLE_AGENT;
            int approvalVersion = state.value(OfficialSupervisorGraphKeys.APPROVAL_VERSION, 0) + 1;
            int retryCount = state.value(OfficialSupervisorGraphKeys.RETRY_COUNT, 0);
            int maxRetryCount = base.maxRetryCount() == null
                    ? state.value(OfficialSupervisorGraphKeys.MAX_RETRY_COUNT, 3)
                    : base.maxRetryCount();
            ApprovalRequest approval = new ApprovalRequest(
                    base.approvalId(),
                    base.taskId(),
                    base.sessionId(),
                    base.approvalType(),
                    base.subjectType(),
                    base.subjectId(),
                    approvalVersion,
                    base.title(),
                    base.summary(),
                    base.payload(),
                    nextNode,
                    "regenerate",
                    "cancel",
                    retryCount,
                    maxRetryCount,
                    base.traceId()
            );
            publish(workflowState, "task.waiting_approval", approval.summary(),
                    Map.of("approvalId", approval.approvalId(), "nextNode", nextNode));
            return Map.of(
                    OfficialSupervisorGraphKeys.WORKFLOW_STATUS, WorkflowStatus.WAITING_USER_APPROVAL.name(),
                    OfficialSupervisorGraphKeys.PENDING_APPROVAL, approval,
                    OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.APPROVAL_GATE,
                    OfficialSupervisorGraphKeys.NEXT_NODE, nextNode,
                    OfficialSupervisorGraphKeys.APPROVAL_VERSION, approval.subjectVersion(),
                    OfficialSupervisorGraphKeys.MAX_RETRY_COUNT,
                    approval.maxRetryCount() == null ? 3 : approval.maxRetryCount()
            );
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction resumeDecision() {
        NodeAction action = state -> {
            ApprovalDecision decision = approvalDecision(state);
            ApprovalRequest pending = approvalRequest(state);
            if (decision == null || pending == null) {
                return error("INVALID_APPROVAL_STATE", "Missing approval decision or pending approval");
            }
            String actionName = switch (decision.status()) {
                case APPROVED -> allowedNextNode(pending.approveNextNode(), "single");
                case REJECTED -> "regenerate";
                case TERMINATED -> "cancel";
                case PENDING -> "fail";
            };
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, decision);
            updates.put(OfficialSupervisorGraphKeys.RESUME_IDEMPOTENCY_KEY, decision.approvalId());
            updates.put(OfficialSupervisorGraphKeys.PENDING_APPROVAL, null);
            updates.put(OfficialSupervisorGraphKeys.RESUME_FEEDBACK,
                    decision.feedback() == null ? "" : decision.feedback());
            if (decision.status() == ApprovalStatus.REJECTED) {
                int retryCount = state.value(OfficialSupervisorGraphKeys.RETRY_COUNT, 0) + 1;
                int maxRetryCount = pending.maxRetryCount() == null
                        ? state.value(OfficialSupervisorGraphKeys.MAX_RETRY_COUNT, 3)
                        : pending.maxRetryCount();
                updates.put(OfficialSupervisorGraphKeys.RETRY_COUNT, retryCount);
                if (retryCount > maxRetryCount) {
                    actionName = "fail";
                    updates.put(OfficialSupervisorGraphKeys.ERROR_CODE,
                            "APPROVAL_RETRY_LIMIT_EXCEEDED");
                    updates.put(OfficialSupervisorGraphKeys.ERROR_MESSAGE,
                            "Approval rejection exceeded the retry limit");
                }
            } else if (decision.status() == ApprovalStatus.PENDING) {
                updates.put(OfficialSupervisorGraphKeys.ERROR_CODE, "INVALID_APPROVAL_DECISION");
                updates.put(OfficialSupervisorGraphKeys.ERROR_MESSAGE,
                        "PENDING is not a valid approval decision");
            }
            updates.put(OfficialSupervisorGraphKeys.APPROVAL_RESUME_ACTION, actionName);
            updates.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS,
                    actionName.equals("fail")
                            ? WorkflowStatus.FAILED.name()
                            : decision.status() == ApprovalStatus.TERMINATED
                            ? WorkflowStatus.CANCELED.name() : WorkflowStatus.RUNNING.name());
            updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.RESUME_DECISION);
            updates.put(OfficialSupervisorGraphKeys.NEXT_NODE, actionName);
            return updates;
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction singleAgent() {
        NodeAction action = state -> {
            try {
                SupervisorGraphState graphState = OfficialGraphStateAdapters.toSupervisorGraphState(state);
                RegisteredAgentDescriptor descriptor = StringUtils.hasText(graphState.selectedAgentId())
                        ? agentRegistry.getByAgentId(graphState.selectedAgentId())
                        .orElseGet(() -> selectAgent(graphState.domain(), graphState.userMessage()))
                        : selectAgent(graphState.domain(), graphState.userMessage());
                SingleAgentSubgraph.ExecutionResult execution = singleAgentSubgraph.execute(
                        requestOf(state), graphState, descriptor);
                if (execution == null || execution.response() == null
                        || isFailedResponse(execution.response())) {
                    String message = execution == null || execution.response() == null
                            ? "Single agent returned no response" : failureMessage(execution.response());
                    Map<String, Object> failed = new LinkedHashMap<>(error(
                            "SINGLE_AGENT_FAILED", message));
                    if (execution != null && execution.response() != null) {
                        failed.put(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, execution.response());
                    }
                    failed.put(OfficialSupervisorGraphKeys.CURRENT_NODE,
                            OfficialSupervisorGraphNodeNames.SINGLE_AGENT);
                    return failed;
                }
                SupervisorWorkflowState previous = OfficialGraphStateAdapters.toWorkflowState(state, objectMapper);
                Map<String, Object> updates = new LinkedHashMap<>(
                        OfficialGraphStateAdapters.toDeltaMap(previous, execution.workflowState()));
                updates.put(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, execution.response());
                updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.SINGLE_AGENT);
                return updates;
            } catch (Exception exception) {
                return error("SINGLE_AGENT_FAILED", messageOf(exception));
            }
        };
        return AsyncNodeAction.node_async(action);
    }

    /**
     * Decides what happens after a completed Agent invocation. This keeps
     * next-hint and parallel route decisions inside the Supervisor Graph;
     * completion is no longer allowed to invoke a legacy service workflow.
     */
    private AsyncNodeAction routeAfterExecution() {
        NodeAction action = state -> {
            try {
                if (StringUtils.hasText(state.value(OfficialSupervisorGraphKeys.ERROR_CODE, (String) null))) {
                    return Map.of(OfficialSupervisorGraphKeys.CURRENT_NODE,
                            OfficialSupervisorGraphNodeNames.ROUTE_AFTER_EXECUTION);
                }
                AgentTaskInvokeResponse response = convert(
                        state.value(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, Object.class).orElse(null),
                        AgentTaskInvokeResponse.class);
                if (response == null) {
                    return error("MISSING_AGENT_RESPONSE", "Supervisor execution produced no Agent response");
                }
                if (isFailedResponse(response)) {
                    return error("AGENT_EXECUTION_FAILED", failureMessage(response));
                }

                HandoffTarget target = resolveHandoffTarget(state, response);
                if (target == null) {
                    Map<String, Object> updates = new LinkedHashMap<>();
                    updates.put(OfficialSupervisorGraphKeys.NEXT_DOMAIN, null);
                    updates.put(OfficialSupervisorGraphKeys.HANDOFF_TYPE, null);
                    updates.put(OfficialSupervisorGraphKeys.NEXT_NODE,
                            OfficialSupervisorGraphNodeNames.COMPLETE);
                    updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE,
                            OfficialSupervisorGraphNodeNames.ROUTE_AFTER_EXECUTION);
                    return updates;
                }

                int handoffCount = state.value(OfficialSupervisorGraphKeys.HANDOFF_COUNT, 0);
                int maxHandoffCount = state.value(OfficialSupervisorGraphKeys.MAX_HANDOFF_COUNT, 5);
                if (handoffCount >= maxHandoffCount) {
                    return error("HANDOFF_LIMIT_EXCEEDED",
                            "Supervisor handoff count exceeded the configured limit");
                }
                Map<String, Object> context = new LinkedHashMap<>(
                        OfficialGraphStateAdapters.sharedContext(state));
                context.put("nextDomain", target.domain());
                context.put("nextIntent", target.intent());
                context.put("routeDecision", target.reason());
                Map<String, Object> updates = new LinkedHashMap<>();
                updates.put(OfficialSupervisorGraphKeys.SHARED_CONTEXT, context);
                updates.put(OfficialSupervisorGraphKeys.NEXT_DOMAIN, target.domain());
                updates.put(OfficialSupervisorGraphKeys.HANDOFF_TYPE, target.type());
                updates.put(OfficialSupervisorGraphKeys.NEXT_NODE, OfficialSupervisorGraphNodeNames.HANDOFF);
                updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE,
                        OfficialSupervisorGraphNodeNames.ROUTE_AFTER_EXECUTION);
                return updates;
            } catch (Exception exception) {
                return error("ROUTE_AFTER_EXECUTION_FAILED", messageOf(exception));
            }
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction handoff() {
        NodeAction action = state -> {
            try {
                String nextDomain = state.value(OfficialSupervisorGraphKeys.NEXT_DOMAIN, (String) null);
                if (!StringUtils.hasText(nextDomain)) {
                    return error("INVALID_HANDOFF_TARGET", "Handoff target domain is missing");
                }
                SupervisorWorkflowState current = OfficialGraphStateAdapters.toWorkflowState(state, objectMapper);
                Map<String, Object> context = buildNextAgentContextNode.build(current, nextDomain);
                String handoffType = state.value(OfficialSupervisorGraphKeys.HANDOFF_TYPE, "next_hint");
                int handoffCount = state.value(OfficialSupervisorGraphKeys.HANDOFF_COUNT, 0);
                SupervisorWorkflowState next = handoffNode.handoff(
                        current, nextDomain, context, handoffType, handoffCount);
                Map<String, Object> updates = new LinkedHashMap<>(
                        OfficialGraphStateAdapters.toDeltaMap(current, next));
                updates.put(OfficialSupervisorGraphKeys.HANDOFF_COUNT, handoffCount + 1);
                updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE,
                        OfficialSupervisorGraphNodeNames.HANDOFF);
                updates.put(OfficialSupervisorGraphKeys.NEXT_NODE,
                        OfficialSupervisorGraphNodeNames.ROUTE_AFTER_EXECUTION);
                return updates;
            } catch (Exception exception) {
                return error("HANDOFF_FAILED", messageOf(exception));
            }
        };
        return AsyncNodeAction.node_async(action);
    }

    private String executionTarget(OverAllState state) {
        if (StringUtils.hasText(state.value(OfficialSupervisorGraphKeys.ERROR_CODE, (String) null))) {
            return "fail";
        }
        return StringUtils.hasText(state.value(OfficialSupervisorGraphKeys.NEXT_DOMAIN, (String) null))
                ? "handoff" : "complete";
    }

    private HandoffTarget resolveHandoffTarget(OverAllState state,
                                               AgentTaskInvokeResponse response) {
        if (response.nextHints() != null) {
            for (String nextHint : response.nextHints()) {
                HandoffTarget target = mapNextHint(nextHint);
                if (target != null) {
                    return target;
                }
            }
        }
        List<String> parallelDomains = castList(state.value(
                OfficialSupervisorGraphKeys.PARALLEL_DOMAINS, List.of()));
        if (parallelDomains.size() > 1) {
            RouteDecisionNode.RouteDecision decision = routeDecisionNode.evaluate(
                    OfficialGraphStateAdapters.sharedContext(state), response);
            if (decision != null && StringUtils.hasText(decision.nextDomain())
                    && PARALLEL_DOMAIN_NODES.containsKey(decision.nextDomain())) {
                return new HandoffTarget(
                        decision.nextDomain(),
                        "".equals(decision.nextDomain()) ? null : resolveIntent(decision.nextDomain()),
                        "route_decision",
                        decision.asMap());
            }
        }
        return null;
    }

    private HandoffTarget mapNextHint(String nextHint) {
        if (!StringUtils.hasText(nextHint)) {
            return null;
        }
        return switch (nextHint) {
            case "marketing.publish_prepare", "marketing.publish" ->
                    new HandoffTarget("marketing", nextHint, "next_hint", nextHint);
            case "notification.send" ->
                    new HandoffTarget("notification", nextHint, "next_hint", nextHint);
            case "settlement.prepare", "settlement.batch" ->
                    new HandoffTarget("settlement", "settlement.prepare", "next_hint", nextHint);
            default -> null;
        };
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

    private boolean isFailedResponse(AgentTaskInvokeResponse response) {
        return response == null || (!"COMPLETED".equalsIgnoreCase(response.status())
                && !"SUCCESS".equalsIgnoreCase(response.status()));
    }

    private String failureMessage(AgentTaskInvokeResponse response) {
        if (response == null) {
            return "Agent response is missing";
        }
        if (StringUtils.hasText(response.summary())) {
            return response.summary();
        }
        return "Agent returned status " + response.status();
    }

    private String messageOf(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record HandoffTarget(String domain, String intent, String type, Object reason) {
    }

    private AsyncNodeAction parallelFanOut() {
        NodeAction action = state -> {
            return Map.of(OfficialSupervisorGraphKeys.CURRENT_NODE,
                    OfficialSupervisorGraphNodeNames.PARALLEL_FAN_OUT);
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction parallelBranch(String domain, String nodeName) {
        NodeAction action = state -> {
            SupervisorGraphState graphState = OfficialGraphStateAdapters.toSupervisorGraphState(state);
            AgentTaskInvokeResponse response;
            try {
                response = parallelInvokeNode.invokeDomain(requestOf(state), graphState, domain);
            } catch (Exception exception) {
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                response = failedParallelResponse(graphState, domain, message);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", state.value(OfficialSupervisorGraphKeys.PARALLEL_RUN_ID, ""));
            result.put("domain", domain);
            result.put("response", response);
            return Map.of(
                    OfficialSupervisorGraphKeys.PARALLEL_BRANCH_RESULTS, List.of(result),
                    OfficialSupervisorGraphKeys.CURRENT_NODE, nodeName
            );
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction parallelAggregate() {
        NodeAction action = state -> {
            try {
                SupervisorGraphState graphState = OfficialGraphStateAdapters.toSupervisorGraphState(state);
                String runId = state.value(OfficialSupervisorGraphKeys.PARALLEL_RUN_ID, "");
                List<Map<String, Object>> branchResults = branchResults(state.value(
                        OfficialSupervisorGraphKeys.PARALLEL_BRANCH_RESULTS, List.of()));
                Map<String, AgentTaskInvokeResponse> responsesByDomain = new HashMap<>();
                for (Map<String, Object> result : branchResults) {
                    if (!runId.equals(String.valueOf(result.get("runId")))) {
                        continue;
                    }
                    String domain = String.valueOf(result.get("domain"));
                    responsesByDomain.put(domain,
                            convert(result.get("response"), AgentTaskInvokeResponse.class));
                }
                List<String> domains = graphState.parallelDomains();
                List<AgentTaskInvokeResponse> responses = domains.stream()
                        .map(responsesByDomain::get)
                        .toList();
                if (responses.stream().anyMatch(java.util.Objects::isNull)) {
                    return error("PARALLEL_FAN_IN_INCOMPLETE",
                            "Native parallel fan-in did not receive every branch result");
                }
                AgentTaskInvokeResponse merged = parallelInvokeNode.mergeResponses(
                        graphState.sessionId(), graphState.taskId(), graphState.traceId(), domains, responses);
                if (!"COMPLETED".equalsIgnoreCase(merged.status())
                        && !"SUCCESS".equalsIgnoreCase(merged.status())) {
                    return error("PARALLEL_BRANCH_FAILED",
                            merged.summary() == null ? "One or more parallel branches failed" : merged.summary());
                }
                List<String> artifactIds = persistParallelArtifactsNode.persist(
                        graphState.taskId(), graphState.sessionId(), graphState.userId(),
                        graphState.traceId(), domains, merged);
                SupervisorWorkflowState previous = OfficialGraphStateAdapters.toWorkflowState(state, objectMapper);
                SupervisorWorkflowState mergedState = mergeParallelResultNode.merge(
                        graphState, artifactIds, merged);
                Map<String, Object> updates = new LinkedHashMap<>(
                        OfficialGraphStateAdapters.toDeltaMap(previous, mergedState));
                updates.put(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, merged);
                updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE,
                        OfficialSupervisorGraphNodeNames.PARALLEL_AGGREGATE);
                return updates;
            } catch (Exception exception) {
                return error("PARALLEL_AGGREGATE_FAILED", messageOf(exception));
            }
        };
        return AsyncNodeAction.node_async(action);
    }

    private MultiCommand parallelTargets(OverAllState state,
                                          com.alibaba.cloud.ai.graph.RunnableConfig config) {
        List<String> domains = castList(state.value(OfficialSupervisorGraphKeys.PARALLEL_DOMAINS, List.of()));
        List<String> branchNodes = domains.stream().map(PARALLEL_DOMAIN_NODES::get).toList();
        if (!validParallelDomains(domains) || branchNodes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException(
                    "parallelDomains must contain at least two distinct supported domains");
        }
        return new MultiCommand(
                domains,
                Map.of(
                        OfficialSupervisorGraphKeys.PARALLEL_RUN_ID, UUID.randomUUID().toString(),
                        OfficialSupervisorGraphKeys.PARALLEL_AGGREGATION_STRATEGY, "ALL_OF",
                        OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.PARALLEL_FAN_OUT
                )
        );
    }

    private Map<String, String> parallelEdgeMappings() {
        return new LinkedHashMap<>(PARALLEL_DOMAIN_NODES);
    }

    private AgentTaskInvokeResponse failedParallelResponse(SupervisorGraphState state,
                                                            String domain,
                                                            String message) {
        return new AgentTaskInvokeResponse(
                state.sessionId(),
                state.taskId(),
                domain + "-agent",
                "FAILED",
                Map.of("domain", domain, "error", message),
                List.of(),
                List.of(),
                message,
                state.traceId()
        );
    }

    private List<Map<String, Object>> branchResults(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(entry -> {
                    Map<?, ?> source = (Map<?, ?>) entry;
                    Map<String, Object> result = new LinkedHashMap<>();
                    source.forEach((key, item) -> result.put(String.valueOf(key), item));
                    return result;
                })
                .toList();
    }

    private AsyncNodeAction regenerate() {
        NodeAction action = state -> {
            Map<String, Object> context = new LinkedHashMap<>(OfficialGraphStateAdapters.sharedContext(state));
            context.put("approvalFeedback", state.value(OfficialSupervisorGraphKeys.RESUME_FEEDBACK, ""));
            context.put("retryCount", state.value(OfficialSupervisorGraphKeys.RETRY_COUNT, 1));
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(OfficialSupervisorGraphKeys.SHARED_CONTEXT, Map.copyOf(context));
            updates.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, WorkflowStatus.RUNNING.name());
            updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.REGENERATE);
            updates.put(OfficialSupervisorGraphKeys.PENDING_APPROVAL, null);
            return updates;
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction complete() {
        NodeAction action = state -> {
            try {
                SupervisorTaskResponse response = completionSubgraph.execute(
                        OfficialGraphStateAdapters.toWorkflowState(state, objectMapper));
                if (response == null || !WorkflowStatus.COMPLETED.name().equalsIgnoreCase(response.status())) {
                    return error("COMPLETION_FAILED", response == null
                            ? "Completion graph returned no response"
                            : "Completion graph returned status " + response.status());
                }
                Map<String, Object> updates = new LinkedHashMap<>();
                updates.put(OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE, response);
                updates.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, response.status());
                updates.put(OfficialSupervisorGraphKeys.FINAL_ANSWER, response.finalAnswer());
                updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.COMPLETE);
                return updates;
            } catch (Exception exception) {
                Map<String, Object> failed = new LinkedHashMap<>(
                        error("COMPLETION_FAILED", messageOf(exception)));
                failed.put(OfficialSupervisorGraphKeys.CURRENT_NODE,
                        OfficialSupervisorGraphNodeNames.COMPLETE);
                return failed;
            }
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction cancel() {
        NodeAction action = state -> {
            String answer = state.value(OfficialSupervisorGraphKeys.RESUME_FEEDBACK, "Workflow terminated");
            SupervisorTaskResponse response = responseOf(state, WorkflowStatus.CANCELED.name(), answer);
            return Map.of(
                    OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE, response,
                    OfficialSupervisorGraphKeys.FINAL_ANSWER, answer,
                    OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.CANCEL
            );
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction completeEnd() {
        NodeAction action = state -> Map.of(
                OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.COMPLETE_END
        );
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction fail() {
        NodeAction action = state -> {
            String message = state.value(OfficialSupervisorGraphKeys.ERROR_MESSAGE, "Supervisor graph failed");
            SupervisorTaskResponse response = responseOf(state, WorkflowStatus.FAILED.name(), message);
            return Map.of(
                    OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE, response,
                    OfficialSupervisorGraphKeys.WORKFLOW_STATUS, WorkflowStatus.FAILED.name(),
                    OfficialSupervisorGraphKeys.FINAL_ANSWER, message,
                    OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.FAIL
            );
        };
        return AsyncNodeAction.node_async(action);
    }

    private String routeTarget(OverAllState state) {
        if (StringUtils.hasText(state.value(OfficialSupervisorGraphKeys.ERROR_CODE, (String) null))) {
            return "fail";
        }
        if (Boolean.TRUE.equals(state.value(OfficialSupervisorGraphKeys.REQUIRE_APPROVAL, false))) {
            return "approval";
        }
        List<String> parallelDomains = castList(state.value(
                OfficialSupervisorGraphKeys.PARALLEL_DOMAINS, List.of()));
        if (parallelDomains.size() > 1) {
            return validParallelDomains(parallelDomains) ? "parallel" : "fail";
        }
        return "single";
    }

    private String resumeTarget(OverAllState state) {
        return switch (state.value(OfficialSupervisorGraphKeys.APPROVAL_RESUME_ACTION, "fail")) {
            case "single", "invoke-next", "complete" -> "single";
            case "parallel", "route-after-parallel" -> "parallel";
            case "regenerate" -> "regenerate";
            case "cancel" -> "cancel";
            default -> "fail";
        };
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of(
                OfficialSupervisorGraphKeys.ERROR_CODE, code,
                OfficialSupervisorGraphKeys.ERROR_MESSAGE, message,
                OfficialSupervisorGraphKeys.WORKFLOW_STATUS, WorkflowStatus.FAILED.name(),
                OfficialSupervisorGraphKeys.APPROVAL_RESUME_ACTION, "fail"
        );
    }

    private ApprovalDecision approvalDecision(OverAllState state) {
        Object value = state.value(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, Object.class).orElse(null);
        return convert(value, ApprovalDecision.class);
    }

    private ApprovalRequest approvalRequest(OverAllState state) {
        Object value = state.value(OfficialSupervisorGraphKeys.PENDING_APPROVAL, Object.class).orElse(null);
        return convert(value, ApprovalRequest.class);
    }

    private <T> T convert(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return objectMapper.convertValue(value, type);
    }

    private String allowedNextNode(String requested, String fallback) {
        return switch (requested == null ? "" : requested) {
            case OfficialSupervisorGraphNodeNames.SINGLE_AGENT -> "single";
            case OfficialSupervisorGraphNodeNames.PARALLEL_AGENTS,
                    OfficialSupervisorGraphNodeNames.PARALLEL_FAN_OUT -> "parallel";
            default -> fallback;
        };
    }

    private SupervisorTaskRequest requestOf(OverAllState state) {
        return new SupervisorTaskRequest(
                state.value(OfficialSupervisorGraphKeys.SESSION_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.USER_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TASK_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TRACE_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.USER_MESSAGE, ""),
                OfficialGraphStateAdapters.sharedContext(state),
                null,
                state.value(OfficialSupervisorGraphKeys.REQUEST_STREAM, false)
        );
    }

    private RegisteredAgentDescriptor selectAgent(String domain, String message) {
        List<RegisteredAgentDescriptor> matched = agentRegistry.findByDomain(domain);
        if (!matched.isEmpty()) {
            return matched.get(0);
        }
        List<RegisteredAgentDescriptor> listing = agentRegistry.findByDomain("listing");
        if (!listing.isEmpty() && containsListingIntent(message)) {
            return listing.get(0);
        }
        return agentRegistry.getByAgentId("listing-agent")
                .or(() -> listing.stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No registered agent available"));
    }

    private SupervisorTaskResponse responseOf(OverAllState state, String status, String answer) {
        return new SupervisorTaskResponse(
                state.value(OfficialSupervisorGraphKeys.SESSION_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TASK_ID, (String) null),
                status,
                answer,
                castList(state.value(OfficialSupervisorGraphKeys.ARTIFACT_IDS, List.of())),
                state.value(OfficialSupervisorGraphKeys.TRACE_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, (String) null),
                Map.of()
        );
    }

    private void publish(SupervisorWorkflowState state,
                         String eventType,
                         String content,
                         Map<String, Object> metadata) {
        sessionStreamService.publish(new SessionStreamEvent(
                state.sessionId(),
                state.taskId(),
                state.selectedAgentId(),
                eventType,
                content,
                metadata == null ? Map.of() : metadata,
                state.traceId(),
                System.currentTimeMillis()
        ));
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    private boolean validParallelDomains(List<String> domains) {
        return domains.size() >= 2
                && domains.stream().allMatch(domain -> domain != null && PARALLEL_DOMAIN_NODES.containsKey(domain))
                && domains.stream().distinct().count() == domains.size();
    }

    private boolean containsListingIntent(String message) {
        return StringUtils.hasText(message) && (message.contains("房源")
                || message.contains("找房")
                || message.contains("小区")
                || message.contains("listing")
                || message.contains("房子"));
    }
}
