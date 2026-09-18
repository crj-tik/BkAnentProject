package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.bkanent.agent.graph.WorkflowResumeSupport;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OfficialRegenerateGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;
    private final WorkflowResumeSupport workflowResumeSupport;
    private final DatabaseCheckpointSaverFactory checkpointSaverFactory;

    public OfficialRegenerateGraphFactory(OfficialSupervisorGraphSchema graphSchema,
                                          WorkflowResumeSupport workflowResumeSupport,
                                          DatabaseCheckpointSaverFactory checkpointSaverFactory) {
        this.graphSchema = graphSchema;
        this.workflowResumeSupport = workflowResumeSupport;
        this.checkpointSaverFactory = checkpointSaverFactory;
    }

    public CompiledGraph create() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(graphSchema.keyStrategyFactory().apply())
                .build();
        StateGraph stateGraph = new StateGraph("official-supervisor-regenerate", keyStrategyFactory);
        stateGraph.addNode(OfficialRegenerateGraphNodeNames.REGENERATE_WORKFLOW, regenerateWorkflow());
        stateGraph.addEdge(StateGraph.START, OfficialRegenerateGraphNodeNames.REGENERATE_WORKFLOW);
        stateGraph.addEdge(OfficialRegenerateGraphNodeNames.REGENERATE_WORKFLOW, StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(
                        checkpointSaverFactory.create("official-regenerate")).build())
                .build());
    }

    private AsyncNodeAction regenerateWorkflow() {
        NodeAction action = state -> Map.of(
                OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE,
                workflowResumeSupport.regenerate(
                        OfficialGraphStateAdapters.toWorkflowState(state),
                        state.value(OfficialSupervisorGraphKeys.RESUME_FEEDBACK, "")
                )
        );
        return AsyncNodeAction.node_async(action);
    }
}
