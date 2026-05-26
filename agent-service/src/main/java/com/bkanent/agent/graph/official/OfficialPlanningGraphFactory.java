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
import com.bkanent.agent.graph.node.LoadSessionNode;
import com.bkanent.agent.graph.node.LlmIntentPlanNode;
import com.bkanent.agent.graph.node.ParseIntentNode;
import com.bkanent.agent.graph.node.PlanValidationNode;
import com.bkanent.agent.graph.node.PlanTaskNode;
import com.bkanent.agent.graph.node.SelectAgentNode;
import org.springframework.stereotype.Component;

@Component
public class OfficialPlanningGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;
    private final LoadSessionNode loadSessionNode;
    private final LlmIntentPlanNode llmIntentPlanNode;
    private final PlanValidationNode planValidationNode;
    private final ParseIntentNode parseIntentNode;
    private final PlanTaskNode planTaskNode;
    private final SelectAgentNode selectAgentNode;

    public OfficialPlanningGraphFactory(OfficialSupervisorGraphSchema graphSchema,
                                        LoadSessionNode loadSessionNode,
                                        LlmIntentPlanNode llmIntentPlanNode,
                                        PlanValidationNode planValidationNode,
                                        ParseIntentNode parseIntentNode,
                                        PlanTaskNode planTaskNode,
                                        SelectAgentNode selectAgentNode) {
        this.graphSchema = graphSchema;
        this.loadSessionNode = loadSessionNode;
        this.llmIntentPlanNode = llmIntentPlanNode;
        this.planValidationNode = planValidationNode;
        this.parseIntentNode = parseIntentNode;
        this.planTaskNode = planTaskNode;
        this.selectAgentNode = selectAgentNode;
    }

    public CompiledGraph create() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(graphSchema.keyStrategyFactory().apply())
                .build();
        StateGraph stateGraph = new StateGraph("official-supervisor-planning", keyStrategyFactory);
        stateGraph.addNode(OfficialGraphNodeNames.LOAD_SESSION, adapt(loadSessionNode));
        stateGraph.addNode(OfficialGraphNodeNames.LLM_INTENT_PLAN, adapt(llmIntentPlanNode));
        stateGraph.addNode(OfficialGraphNodeNames.PLAN_VALIDATION, adapt(planValidationNode));
        stateGraph.addNode(OfficialGraphNodeNames.PARSE_INTENT, adapt(parseIntentNode));
        stateGraph.addNode(OfficialGraphNodeNames.PLAN_TASK, adapt(planTaskNode));
        stateGraph.addNode(OfficialGraphNodeNames.SELECT_AGENT, adapt(selectAgentNode));
        stateGraph.addEdge(StateGraph.START, OfficialGraphNodeNames.LOAD_SESSION);
        stateGraph.addEdge(OfficialGraphNodeNames.LOAD_SESSION, OfficialGraphNodeNames.LLM_INTENT_PLAN);
        stateGraph.addEdge(OfficialGraphNodeNames.LLM_INTENT_PLAN, OfficialGraphNodeNames.PLAN_VALIDATION);
        stateGraph.addEdge(OfficialGraphNodeNames.PLAN_VALIDATION, OfficialGraphNodeNames.PARSE_INTENT);
        stateGraph.addEdge(OfficialGraphNodeNames.PARSE_INTENT, OfficialGraphNodeNames.PLAN_TASK);
        stateGraph.addEdge(OfficialGraphNodeNames.PLAN_TASK, OfficialGraphNodeNames.SELECT_AGENT);
        stateGraph.addEdge(OfficialGraphNodeNames.SELECT_AGENT, StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build());
    }

    private AsyncNodeAction adapt(com.bkanent.agent.graph.SupervisorGraphNode node) {
        NodeAction action = state -> OfficialGraphStateAdapters.toMap(
                node.apply(OfficialGraphStateAdapters.toSupervisorGraphState(state))
        );
        return AsyncNodeAction.node_async(action);
    }
}
