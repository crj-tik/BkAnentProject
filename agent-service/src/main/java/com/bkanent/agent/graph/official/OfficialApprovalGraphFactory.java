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
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OfficialApprovalGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;

    public OfficialApprovalGraphFactory(OfficialSupervisorGraphSchema graphSchema) {
        this.graphSchema = graphSchema;
    }

    public CompiledGraph create() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(graphSchema.keyStrategyFactory().apply())
                .build();
        StateGraph stateGraph = new StateGraph("official-supervisor-approval", keyStrategyFactory);
        stateGraph.addNode(OfficialApprovalGraphNodeNames.ENTER_WAITING_APPROVAL, enterWaitingApproval());
        stateGraph.addEdge(StateGraph.START, OfficialApprovalGraphNodeNames.ENTER_WAITING_APPROVAL);
        stateGraph.addEdge(OfficialApprovalGraphNodeNames.ENTER_WAITING_APPROVAL, StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build());
    }

    private AsyncNodeAction enterWaitingApproval() {
        NodeAction action = state -> Map.of(
                OfficialSupervisorGraphKeys.WORKFLOW_STATUS, WorkflowStatus.WAITING_USER_APPROVAL.name(),
                OfficialSupervisorGraphKeys.PENDING_APPROVAL, state.value(OfficialSupervisorGraphKeys.PENDING_APPROVAL, Object.class).orElse(null)
        );
        return AsyncNodeAction.node_async(action);
    }
}
