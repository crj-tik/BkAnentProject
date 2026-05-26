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
import com.bkanent.agent.graph.node.MergeParallelResultNode;
import com.bkanent.agent.graph.node.ParallelInvokeNode;
import com.bkanent.agent.graph.node.PersistParallelArtifactsNode;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OfficialParallelGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;
    private final ParallelInvokeNode parallelInvokeNode;
    private final PersistParallelArtifactsNode persistParallelArtifactsNode;
    private final MergeParallelResultNode mergeParallelResultNode;

    public OfficialParallelGraphFactory(OfficialSupervisorGraphSchema graphSchema,
                                        ParallelInvokeNode parallelInvokeNode,
                                        PersistParallelArtifactsNode persistParallelArtifactsNode,
                                        MergeParallelResultNode mergeParallelResultNode) {
        this.graphSchema = graphSchema;
        this.parallelInvokeNode = parallelInvokeNode;
        this.persistParallelArtifactsNode = persistParallelArtifactsNode;
        this.mergeParallelResultNode = mergeParallelResultNode;
    }

    public CompiledGraph create() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new KeyStrategyFactoryBuilder()
                .addStrategies(graphSchema.keyStrategyFactory().apply())
                .build();
        StateGraph stateGraph = new StateGraph("official-supervisor-parallel", keyStrategyFactory);
        stateGraph.addNode(OfficialParallelGraphNodeNames.PARALLEL_INVOKE, parallelInvoke());
        stateGraph.addNode(OfficialParallelGraphNodeNames.PERSIST_PARALLEL_ARTIFACTS, persistParallelArtifacts());
        stateGraph.addNode(OfficialParallelGraphNodeNames.MERGE_PARALLEL_RESULT, mergeParallelResult());
        stateGraph.addEdge(StateGraph.START, OfficialParallelGraphNodeNames.PARALLEL_INVOKE);
        stateGraph.addEdge(OfficialParallelGraphNodeNames.PARALLEL_INVOKE, OfficialParallelGraphNodeNames.PERSIST_PARALLEL_ARTIFACTS);
        stateGraph.addEdge(OfficialParallelGraphNodeNames.PERSIST_PARALLEL_ARTIFACTS, OfficialParallelGraphNodeNames.MERGE_PARALLEL_RESULT);
        stateGraph.addEdge(OfficialParallelGraphNodeNames.MERGE_PARALLEL_RESULT, StateGraph.END);
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build());
    }

    private AsyncNodeAction parallelInvoke() {
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
            AgentTaskInvokeResponse response = parallelInvokeNode.invoke(request, graphState);
            return Map.of(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, response);
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction persistParallelArtifacts() {
        NodeAction action = state -> {
            AgentTaskInvokeResponse response = OfficialGraphStateAdapters.latestResponse(state);
            String taskId = state.value(OfficialSupervisorGraphKeys.TASK_ID, (String) null);
            String sessionId = state.value(OfficialSupervisorGraphKeys.SESSION_ID, (String) null);
            String userId = state.value(OfficialSupervisorGraphKeys.USER_ID, (String) null);
            String traceId = state.value(OfficialSupervisorGraphKeys.TRACE_ID, (String) null);
            List<String> parallelDomains = castDomains(state.value(OfficialSupervisorGraphKeys.PARALLEL_DOMAINS, List.of()));
            List<String> artifactIds = persistParallelArtifactsNode.persist(taskId, sessionId, userId, traceId, parallelDomains, response);
            return Map.of(OfficialSupervisorGraphKeys.ARTIFACT_IDS, artifactIds);
        };
        return AsyncNodeAction.node_async(action);
    }

    private AsyncNodeAction mergeParallelResult() {
        NodeAction action = state -> {
            SupervisorGraphState graphState = OfficialGraphStateAdapters.toSupervisorGraphState(state);
            List<String> artifactIds = castDomains(state.value(OfficialSupervisorGraphKeys.ARTIFACT_IDS, List.of()));
            AgentTaskInvokeResponse response = OfficialGraphStateAdapters.latestResponse(state);
            var workflowState = mergeParallelResultNode.merge(graphState, artifactIds, response);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, WorkflowStatus.RUNNING.name());
            updates.put(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, workflowState.selectedAgentId());
            updates.put(OfficialSupervisorGraphKeys.SHARED_CONTEXT, workflowState.sharedContext());
            updates.put(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, workflowState.handoffHistory());
            updates.put(OfficialSupervisorGraphKeys.ARTIFACT_IDS, workflowState.artifactIds());
            updates.put(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, workflowState.latestAgentResponse());
            return updates;
        };
        return AsyncNodeAction.node_async(action);
    }

    @SuppressWarnings("unchecked")
    private List<String> castDomains(List<?> values) {
        return (List<String>) values;
    }
}
