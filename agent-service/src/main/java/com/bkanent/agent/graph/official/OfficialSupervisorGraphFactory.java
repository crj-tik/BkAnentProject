package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.bkanent.agent.graph.ParallelAgentSubgraph;
import com.bkanent.agent.graph.SingleAgentSubgraph;
import com.bkanent.agent.graph.SupervisorGraphPlanner;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.graph.CompletionSubgraph;
import com.bkanent.agent.graph.node.BuildApprovalRequestNode;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.ApprovalDecision;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.agent.ApprovalStatus;
import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.agent.WorkflowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single top-level Supervisor workflow. Domain services are invoked by
 * graph nodes, while all business transitions are represented by graph edges.
 */
@Component
public class OfficialSupervisorGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;
    private final DatabaseCheckpointSaverFactory checkpointSaverFactory;
    private final SupervisorGraphPlanner supervisorGraphPlanner;
    private final SingleAgentSubgraph singleAgentSubgraph;
    private final ParallelAgentSubgraph parallelAgentSubgraph;
    private final CompletionSubgraph completionSubgraph;
    private final BuildApprovalRequestNode buildApprovalRequestNode;
    private final AgentRegistry agentRegistry;
    private final ObjectMapper objectMapper;
    private final SessionStreamService sessionStreamService;

    public OfficialSupervisorGraphFactory(OfficialSupervisorGraphSchema graphSchema,
                                          DatabaseCheckpointSaverFactory checkpointSaverFactory,
                                          SupervisorGraphPlanner supervisorGraphPlanner,
                                          SingleAgentSubgraph singleAgentSubgraph,
                                          ParallelAgentSubgraph parallelAgentSubgraph,
                                          CompletionSubgraph completionSubgraph,
                                          BuildApprovalRequestNode buildApprovalRequestNode,
                                          AgentRegistry agentRegistry,
                                          ObjectMapper objectMapper,
                                          SessionStreamService sessionStreamService) {
        this.graphSchema = graphSchema;
        this.checkpointSaverFactory = checkpointSaverFactory;
        this.supervisorGraphPlanner = supervisorGraphPlanner;
        this.singleAgentSubgraph = singleAgentSubgraph;
        this.parallelAgentSubgraph = parallelAgentSubgraph;
        this.completionSubgraph = completionSubgraph;
        this.buildApprovalRequestNode = buildApprovalRequestNode;
        this.agentRegistry = agentRegistry;
        this.objectMapper = objectMapper;
        this.sessionStreamService = sessionStreamService;
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
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.PARALLEL_AGENTS, parallelAgents());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.REGENERATE, regenerate());
        stateGraph.addNode(OfficialSupervisorGraphNodeNames.COMPLETE, complete());
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
                        "parallel", OfficialSupervisorGraphNodeNames.PARALLEL_AGENTS,
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
                        "parallel", OfficialSupervisorGraphNodeNames.PARALLEL_AGENTS,
                        "regenerate", OfficialSupervisorGraphNodeNames.REGENERATE,
                        "cancel", OfficialSupervisorGraphNodeNames.CANCEL,
                        "fail", OfficialSupervisorGraphNodeNames.FAIL
                )
        );
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.REGENERATE, OfficialSupervisorGraphNodeNames.PLAN);
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.SINGLE_AGENT, OfficialSupervisorGraphNodeNames.COMPLETE);
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.PARALLEL_AGENTS, OfficialSupervisorGraphNodeNames.COMPLETE);
        stateGraph.addEdge(OfficialSupervisorGraphNodeNames.COMPLETE, StateGraph.END);
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
            SupervisorWorkflowState workflowState = OfficialGraphStateAdapters.toWorkflowState(state, objectMapper);
            ApprovalRequest base = buildApprovalRequestNode.build(workflowState, graphState.sharedContext());
            String nextNode = graphState.parallelDomains().size() > 1
                    ? OfficialSupervisorGraphNodeNames.PARALLEL_AGENTS
                    : OfficialSupervisorGraphNodeNames.SINGLE_AGENT;
            int approvalVersion = state.value(OfficialSupervisorGraphKeys.APPROVAL_VERSION, 0) + 1;
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
                    base.retryCount(),
                    base.maxRetryCount(),
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
            updates.put(OfficialSupervisorGraphKeys.APPROVAL_RESUME_ACTION, actionName);
            updates.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS,
                    decision.status() == ApprovalStatus.TERMINATED
                            ? WorkflowStatus.CANCELED.name() : WorkflowStatus.RUNNING.name());
            updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.RESUME_DECISION);
            updates.put(OfficialSupervisorGraphKeys.NEXT_NODE, actionName);
            if (decision.status() == ApprovalStatus.REJECTED) {
                updates.put(OfficialSupervisorGraphKeys.RETRY_COUNT,
                        (pending.retryCount() == null ? 0 : pending.retryCount()) + 1);
                updates.put(OfficialSupervisorGraphKeys.RESUME_FEEDBACK,
                        decision.feedback() == null ? "" : decision.feedback());
            }
            return updates;
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction singleAgent() {
        NodeAction action = state -> {
            SupervisorGraphState graphState = OfficialGraphStateAdapters.toSupervisorGraphState(state);
            RegisteredAgentDescriptor descriptor = agentRegistry.getByAgentId(graphState.selectedAgentId())
                    .orElseGet(() -> selectAgent(graphState.domain(), graphState.userMessage()));
            SingleAgentSubgraph.ExecutionResult execution = singleAgentSubgraph.execute(
                    requestOf(state), graphState, descriptor);
            SupervisorWorkflowState previous = OfficialGraphStateAdapters.toWorkflowState(state, objectMapper);
            Map<String, Object> updates = new LinkedHashMap<>(
                    OfficialGraphStateAdapters.toDeltaMap(previous, execution.workflowState()));
            updates.put(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, execution.response());
            updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.SINGLE_AGENT);
            return updates;
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction parallelAgents() {
        NodeAction action = state -> {
            SupervisorGraphState graphState = OfficialGraphStateAdapters.toSupervisorGraphState(state);
            ParallelAgentSubgraph.ExecutionResult execution = parallelAgentSubgraph.execute(
                    requestOf(state), graphState);
            SupervisorWorkflowState previous = OfficialGraphStateAdapters.toWorkflowState(state, objectMapper);
            Map<String, Object> updates = new LinkedHashMap<>(
                    OfficialGraphStateAdapters.toDeltaMap(previous, execution.workflowState()));
            updates.put(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, execution.response());
            updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.PARALLEL_AGENTS);
            return updates;
        };
        return AsyncNodeAction.node_async(action);
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
            SupervisorTaskResponse response = completionSubgraph.execute(
                    OfficialGraphStateAdapters.toWorkflowState(state, objectMapper));
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE, response);
            updates.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, response.status());
            updates.put(OfficialSupervisorGraphKeys.FINAL_ANSWER, response.finalAnswer());
            updates.put(OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.COMPLETE);
            return updates;
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
        return parallelDomains.size() > 1 ? "parallel" : "single";
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
            case OfficialSupervisorGraphNodeNames.PARALLEL_AGENTS -> "parallel";
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

    private boolean containsListingIntent(String message) {
        return StringUtils.hasText(message) && (message.contains("房源")
                || message.contains("找房")
                || message.contains("小区")
                || message.contains("listing")
                || message.contains("房子"));
    }
}
