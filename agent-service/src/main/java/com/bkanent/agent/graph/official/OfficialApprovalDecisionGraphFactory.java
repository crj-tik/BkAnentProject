package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.bkanent.common.agent.ApprovalDecision;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.agent.ApprovalStatus;
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OfficialApprovalDecisionGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;

    public OfficialApprovalDecisionGraphFactory(OfficialSupervisorGraphSchema graphSchema) {
        this.graphSchema = graphSchema;
    }

    public CompiledGraph create() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(graphSchema.keyStrategyFactory().apply())
                .build();
        StateGraph stateGraph = new StateGraph("official-supervisor-approval-decision", keyStrategyFactory);
        stateGraph.addNode(OfficialApprovalDecisionGraphNodeNames.APPLY_APPROVAL_DECISION, applyDecision());
        stateGraph.addEdge(StateGraph.START, OfficialApprovalDecisionGraphNodeNames.APPLY_APPROVAL_DECISION);
        stateGraph.addEdge(OfficialApprovalDecisionGraphNodeNames.APPLY_APPROVAL_DECISION, StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build());
    }

    private AsyncNodeAction applyDecision() {
        NodeAction action = state -> {
            ApprovalDecision decision = state.value(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, ApprovalDecision.class)
                    .orElseThrow(() -> new IllegalStateException("Missing latest approval decision"));
            ApprovalRequest pendingApproval = state.value(OfficialSupervisorGraphKeys.PENDING_APPROVAL, ApprovalRequest.class)
                    .orElse(null);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, decision);
            updates.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, mapStatus(decision.status()).name());
            updates.put(OfficialSupervisorGraphKeys.APPROVAL_RESUME_ACTION, resolveResumeAction(decision.status(), pendingApproval));
            return updates;
        };
        return AsyncNodeAction.node_async(action);
    }

    private String resolveResumeAction(ApprovalStatus status, ApprovalRequest pendingApproval) {
        if (pendingApproval == null) {
            return switch (status) {
                case APPROVED -> "complete";
                case REJECTED -> "regenerate";
                case TERMINATED -> "cancel";
                case PENDING -> "wait";
            };
        }
        return switch (status) {
            case APPROVED -> defaultRoute(pendingApproval.approveNextNode(), "complete");
            case REJECTED -> defaultRoute(pendingApproval.rejectNextNode(), "regenerate");
            case TERMINATED -> defaultRoute(pendingApproval.terminateNextNode(), "cancel");
            case PENDING -> "wait";
        };
    }

    private String defaultRoute(String route, String fallback) {
        return (route == null || route.isBlank()) ? fallback : route;
    }

    private WorkflowStatus mapStatus(ApprovalStatus status) {
        return switch (status) {
            case PENDING -> WorkflowStatus.WAITING_USER_APPROVAL;
            case APPROVED, REJECTED -> WorkflowStatus.RUNNING;
            case TERMINATED -> WorkflowStatus.CANCELED;
        };
    }
}
