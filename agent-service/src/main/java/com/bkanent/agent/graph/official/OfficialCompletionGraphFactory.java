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
public class OfficialCompletionGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;
    private final WorkflowResumeSupport workflowResumeSupport;

    public OfficialCompletionGraphFactory(OfficialSupervisorGraphSchema graphSchema,
                                          WorkflowResumeSupport workflowResumeSupport) {
        this.graphSchema = graphSchema;
        this.workflowResumeSupport = workflowResumeSupport;
    }

    public CompiledGraph create() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(graphSchema.keyStrategyFactory().apply())
                .build();
        StateGraph stateGraph = new StateGraph("official-supervisor-completion", keyStrategyFactory);
        stateGraph.addNode(OfficialCompletionGraphNodeNames.COMPLETE_WORKFLOW, completeWorkflow());
        stateGraph.addEdge(StateGraph.START, OfficialCompletionGraphNodeNames.COMPLETE_WORKFLOW);
        stateGraph.addEdge(OfficialCompletionGraphNodeNames.COMPLETE_WORKFLOW, StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build());
    }

    private AsyncNodeAction completeWorkflow() {
        NodeAction action = state -> Map.of(
                OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE,
                workflowResumeSupport.complete(
                        OfficialGraphStateAdapters.toWorkflowState(state),
                        null,
                        Map.of("status", "COMPLETED")
                )
        );
        return AsyncNodeAction.node_async(action);
    }
}
