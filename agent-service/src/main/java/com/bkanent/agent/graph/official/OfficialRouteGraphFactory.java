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
import com.bkanent.agent.graph.WorkflowResumeSupport;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OfficialRouteGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;
    private final WorkflowResumeSupport workflowResumeSupport;

    public OfficialRouteGraphFactory(OfficialSupervisorGraphSchema graphSchema,
                                     WorkflowResumeSupport workflowResumeSupport) {
        this.graphSchema = graphSchema;
        this.workflowResumeSupport = workflowResumeSupport;
    }

    public CompiledGraph create() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(graphSchema.keyStrategyFactory().apply())
                .build();
        StateGraph stateGraph = new StateGraph("official-supervisor-route", keyStrategyFactory);
        stateGraph.addNode(OfficialRouteGraphNodeNames.ROUTE_AFTER_PARALLEL, routeAfterParallel());
        stateGraph.addEdge(StateGraph.START, OfficialRouteGraphNodeNames.ROUTE_AFTER_PARALLEL);
        stateGraph.addEdge(OfficialRouteGraphNodeNames.ROUTE_AFTER_PARALLEL, StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build());
    }

    private AsyncNodeAction routeAfterParallel() {
        NodeAction action = state -> Map.of(
                OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE,
                workflowResumeSupport.routeAfterParallel(OfficialGraphStateAdapters.toWorkflowState(state))
        );
        return AsyncNodeAction.node_async(action);
    }
}
