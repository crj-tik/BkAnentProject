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
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.graph.node.BuildInvokeRequestNode;
import com.bkanent.agent.graph.node.InvokeAgentNode;
import com.bkanent.agent.graph.node.MergeAgentResultNode;
import com.bkanent.agent.graph.node.PersistArtifactsNode;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OfficialSingleAgentGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;
    private final BuildInvokeRequestNode buildInvokeRequestNode;
    private final InvokeAgentNode invokeAgentNode;
    private final PersistArtifactsNode persistArtifactsNode;
    private final MergeAgentResultNode mergeAgentResultNode;
    private final AgentRegistry agentRegistry;

    public OfficialSingleAgentGraphFactory(OfficialSupervisorGraphSchema graphSchema,
                                           BuildInvokeRequestNode buildInvokeRequestNode,
                                           InvokeAgentNode invokeAgentNode,
                                           PersistArtifactsNode persistArtifactsNode,
                                           MergeAgentResultNode mergeAgentResultNode,
                                           AgentRegistry agentRegistry) {
        this.graphSchema = graphSchema;
        this.buildInvokeRequestNode = buildInvokeRequestNode;
        this.invokeAgentNode = invokeAgentNode;
        this.persistArtifactsNode = persistArtifactsNode;
        this.mergeAgentResultNode = mergeAgentResultNode;
        this.agentRegistry = agentRegistry;
    }

    public CompiledGraph create() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(graphSchema.keyStrategyFactory().apply())
                .build();
        StateGraph stateGraph = new StateGraph("official-supervisor-single-agent", keyStrategyFactory);
        stateGraph.addNode(OfficialSingleAgentGraphNodeNames.BUILD_INVOKE_REQUEST, buildInvokeRequest());
        stateGraph.addNode(OfficialSingleAgentGraphNodeNames.INVOKE_AGENT, invokeAgent());
        stateGraph.addNode(OfficialSingleAgentGraphNodeNames.PERSIST_ARTIFACTS, persistArtifacts());
        stateGraph.addNode(OfficialSingleAgentGraphNodeNames.MERGE_RESULT, mergeResult());
        stateGraph.addEdge(StateGraph.START, OfficialSingleAgentGraphNodeNames.BUILD_INVOKE_REQUEST);
        stateGraph.addEdge(OfficialSingleAgentGraphNodeNames.BUILD_INVOKE_REQUEST, OfficialSingleAgentGraphNodeNames.INVOKE_AGENT);
        stateGraph.addEdge(OfficialSingleAgentGraphNodeNames.INVOKE_AGENT, OfficialSingleAgentGraphNodeNames.PERSIST_ARTIFACTS);
        stateGraph.addEdge(OfficialSingleAgentGraphNodeNames.PERSIST_ARTIFACTS, OfficialSingleAgentGraphNodeNames.MERGE_RESULT);
        stateGraph.addEdge(OfficialSingleAgentGraphNodeNames.MERGE_RESULT, StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build());
    }

    private AsyncNodeAction buildInvokeRequest() {
        NodeAction action = state -> {
            SupervisorGraphState graphState = OfficialGraphStateAdapters.toSupervisorGraphState(state);
            SupervisorTaskRequest request = new SupervisorTaskRequest(
                    graphState.sessionId(),
                    graphState.userId(),
                    graphState.taskId(),
                    graphState.traceId(),
                    graphState.userMessage(),
                    graphState.sharedContext(),
                    null,
                    state.value(OfficialSupervisorGraphKeys.REQUEST_STREAM, false)
            );
            String targetAgentId = state.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, (String) null);
            AgentTaskInvokeRequest invokeRequest = buildInvokeRequestNode.build(request, graphState, targetAgentId, null, 0);
            return Map.of(OfficialSupervisorGraphKeys.CURRENT_INVOKE_REQUEST, invokeRequest);
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction invokeAgent() {
        NodeAction action = state -> {
            AgentTaskInvokeRequest invokeRequest = OfficialGraphStateAdapters.currentInvokeRequest(state);
            String targetAgentId = state.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, (String) null);
            RegisteredAgentDescriptor descriptor = agentRegistry.getByAgentId(targetAgentId)
                    .orElseThrow(() -> new IllegalStateException("No agent descriptor for " + targetAgentId));
            AgentTaskInvokeResponse response = invokeAgentNode.invoke(descriptor, invokeRequest);
            return Map.of(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, response);
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction persistArtifacts() {
        NodeAction action = state -> {
            AgentTaskInvokeResponse response = OfficialGraphStateAdapters.latestResponse(state);
            String taskId = state.value(OfficialSupervisorGraphKeys.TASK_ID, (String) null);
            String sessionId = state.value(OfficialSupervisorGraphKeys.SESSION_ID, (String) null);
            String agentId = state.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, (String) null);
            String userId = state.value(OfficialSupervisorGraphKeys.USER_ID, (String) null);
            String traceId = state.value(OfficialSupervisorGraphKeys.TRACE_ID, (String) null);
            List<String> artifactIds = persistArtifactsNode.persistSingle(taskId, sessionId, agentId, userId, traceId, response);
            return Map.of(OfficialSupervisorGraphKeys.ARTIFACT_IDS, artifactIds);
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction mergeResult() {
        NodeAction action = state -> {
            SupervisorGraphState graphState = OfficialGraphStateAdapters.toSupervisorGraphState(state);
            String selectedAgentId = state.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, (String) null);
            List<String> artifactIds = castArtifactIds(state.value(OfficialSupervisorGraphKeys.ARTIFACT_IDS, List.of()));
            AgentTaskInvokeResponse response = OfficialGraphStateAdapters.latestResponse(state);
            var workflowState = mergeAgentResultNode.mergeSingle(graphState, selectedAgentId, artifactIds, response);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, WorkflowStatus.RUNNING.name());
            updates.put(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, workflowState.handoffHistory());
            updates.put(OfficialSupervisorGraphKeys.ARTIFACT_IDS, workflowState.artifactIds());
            updates.put(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, workflowState.latestAgentResponse());
            return updates;
        };
        return AsyncNodeAction.node_async(action);
    }

    @SuppressWarnings("unchecked")
    private List<String> castArtifactIds(List<?> values) {
        return (List<String>) values;
    }
}
