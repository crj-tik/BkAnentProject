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
import com.bkanent.agent.graph.node.BuildNextAgentContextNode;
import com.bkanent.agent.graph.node.HandoffNode;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OfficialHandoffGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;
    private final BuildNextAgentContextNode buildNextAgentContextNode;
    private final HandoffNode handoffNode;

    public OfficialHandoffGraphFactory(OfficialSupervisorGraphSchema graphSchema,
                                       BuildNextAgentContextNode buildNextAgentContextNode,
                                       HandoffNode handoffNode) {
        this.graphSchema = graphSchema;
        this.buildNextAgentContextNode = buildNextAgentContextNode;
        this.handoffNode = handoffNode;
    }

    public CompiledGraph create() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(graphSchema.keyStrategyFactory().apply())
                .build();
        StateGraph stateGraph = new StateGraph("official-supervisor-handoff", keyStrategyFactory);
        stateGraph.addNode(OfficialHandoffGraphNodeNames.BUILD_NEXT_AGENT_CONTEXT, buildNextAgentContext());
        stateGraph.addNode(OfficialHandoffGraphNodeNames.HANDOFF_INVOKE, handoffInvoke());
        stateGraph.addEdge(StateGraph.START, OfficialHandoffGraphNodeNames.BUILD_NEXT_AGENT_CONTEXT);
        stateGraph.addEdge(OfficialHandoffGraphNodeNames.BUILD_NEXT_AGENT_CONTEXT, OfficialHandoffGraphNodeNames.HANDOFF_INVOKE);
        stateGraph.addEdge(OfficialHandoffGraphNodeNames.HANDOFF_INVOKE, StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build());
    }

    private AsyncNodeAction buildNextAgentContext() {
        NodeAction action = state -> {
            SupervisorWorkflowState workflowState = OfficialGraphStateAdapters.toWorkflowState(state);
            String nextDomain = state.value(OfficialSupervisorGraphKeys.NEXT_DOMAIN, (String) null);
            Map<String, Object> context = new LinkedHashMap<>(buildNextAgentContextNode.build(workflowState, nextDomain));
            context.put(OfficialSupervisorGraphKeys.NEXT_DOMAIN, nextDomain);
            context.putIfAbsent(OfficialSupervisorGraphKeys.HANDOFF_TYPE,
                    state.value(OfficialSupervisorGraphKeys.HANDOFF_TYPE, "next_agent"));
            return Map.of(OfficialSupervisorGraphKeys.SHARED_CONTEXT, context);
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction handoffInvoke() {
        NodeAction action = state -> {
            SupervisorWorkflowState workflowState = OfficialGraphStateAdapters.toWorkflowState(state);
            String nextDomain = state.value(OfficialSupervisorGraphKeys.NEXT_DOMAIN, (String) null);
            String handoffType = state.value(OfficialSupervisorGraphKeys.HANDOFF_TYPE, "next_agent");
            Map<String, Object> context = OfficialGraphStateAdapters.sharedContext(state);
            SupervisorWorkflowState nextState = handoffNode.handoff(workflowState, nextDomain, context, handoffType);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, WorkflowStatus.RUNNING.name());
            updates.put(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, nextState.selectedAgentId());
            updates.put(OfficialSupervisorGraphKeys.SHARED_CONTEXT, nextState.sharedContext());
            updates.put(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, nextState.handoffHistory());
            updates.put(OfficialSupervisorGraphKeys.ARTIFACT_IDS, nextState.artifactIds());
            updates.put(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, nextState.latestAgentResponse());
            return updates;
        };
        return AsyncNodeAction.node_async(action);
    }
}
