package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.bkanent.agent.graph.node.PersistSessionNode;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OfficialCompletionGraphFactory {

    private final OfficialSupervisorGraphSchema graphSchema;
    private final DatabaseCheckpointSaverFactory checkpointSaverFactory;
    private final PersistSessionNode persistSessionNode;
    private final SessionStreamService sessionStreamService;

    public OfficialCompletionGraphFactory(OfficialSupervisorGraphSchema graphSchema,
                                          DatabaseCheckpointSaverFactory checkpointSaverFactory,
                                          PersistSessionNode persistSessionNode,
                                          SessionStreamService sessionStreamService) {
        this.graphSchema = graphSchema;
        this.checkpointSaverFactory = checkpointSaverFactory;
        this.persistSessionNode = persistSessionNode;
        this.sessionStreamService = sessionStreamService;
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
                .saverConfig(SaverConfig.builder().register(
                        checkpointSaverFactory.create("official-completion")).build())
                .build());
    }

    private AsyncNodeAction completeWorkflow() {
        NodeAction action = state -> {
            SupervisorWorkflowState workflowState = OfficialGraphStateAdapters.toWorkflowState(state);
            AgentTaskInvokeResponse agentResponse = workflowState.latestAgentResponse();
            if (agentResponse == null) {
                throw new IllegalStateException("Cannot complete supervisor workflow without agent response");
            }
            if (!"COMPLETED".equalsIgnoreCase(agentResponse.status())
                    && !"SUCCESS".equalsIgnoreCase(agentResponse.status())) {
                throw new IllegalStateException("Cannot complete supervisor workflow with agent status "
                        + agentResponse.status());
            }
            String answer = StringUtils.hasText(workflowState.finalAnswer())
                    ? workflowState.finalAnswer()
                    : StringUtils.hasText(agentResponse.summary())
                    ? agentResponse.summary() : "Workflow finished.";
            SupervisorWorkflowState completed = workflowState
                    .withWorkflowStatus(WorkflowStatus.COMPLETED)
                    .withFinalAnswer(answer);
            persistSessionNode.persist(completed, answer);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("status", WorkflowStatus.COMPLETED.name());
            metadata.put("artifactCount", completed.artifactIds() == null
                    ? 0 : completed.artifactIds().size());
            sessionStreamService.publish(new SessionStreamEvent(
                    completed.sessionId(),
                    completed.taskId(),
                    completed.selectedAgentId(),
                    "task.completed",
                    answer,
                    metadata,
                    completed.traceId(),
                    System.currentTimeMillis()
            ));
            SupervisorTaskResponse response = new SupervisorTaskResponse(
                    completed.sessionId(),
                    completed.taskId(),
                    WorkflowStatus.COMPLETED.name(),
                    answer,
                    completed.artifactIds(),
                    completed.traceId(),
                    completed.selectedAgentId(),
                    Map.of()
            );
            return Map.of(
                    OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE, response,
                    OfficialSupervisorGraphKeys.WORKFLOW_STATUS, WorkflowStatus.COMPLETED.name(),
                    OfficialSupervisorGraphKeys.FINAL_ANSWER, answer,
                    OfficialSupervisorGraphKeys.CURRENT_NODE, OfficialSupervisorGraphNodeNames.COMPLETE
            );
        };
        return AsyncNodeAction.node_async(action);
    }
}
