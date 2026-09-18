package com.bkanent.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bkanent.agent.graph.official.OfficialGraphStateAdapters;
import com.bkanent.agent.graph.official.OfficialSingleAgentGraphHolder;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphKeys;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphMigrationFacade;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class SingleAgentSubgraph {

    private static final String SUBGRAPH_NAME = "single_agent";

    private final OfficialSingleAgentGraphHolder officialSingleAgentGraphHolder;
    private final OfficialSupervisorGraphMigrationFacade migrationFacade;
    private final GraphAuditService graphAuditService;
    private final SessionStreamService sessionStreamService;

    public SingleAgentSubgraph(OfficialSingleAgentGraphHolder officialSingleAgentGraphHolder,
                               OfficialSupervisorGraphMigrationFacade migrationFacade,
                               GraphAuditService graphAuditService,
                               SessionStreamService sessionStreamService) {
        this.officialSingleAgentGraphHolder = officialSingleAgentGraphHolder;
        this.migrationFacade = migrationFacade;
        this.graphAuditService = graphAuditService;
        this.sessionStreamService = sessionStreamService;
    }

    public ExecutionResult execute(SupervisorTaskRequest request,
                                   SupervisorGraphState graphState,
                                   RegisteredAgentDescriptor descriptor) {
        long startedAt = graphAuditService.markStart(
                graphState.sessionId(),
                graphState.taskId(),
                graphState.traceId(),
                SUBGRAPH_NAME,
                Map.of("selectedAgentId", descriptor.agentId(), "domain", graphState.domain())
        );
        Map<String, Object> state = new LinkedHashMap<>(OfficialGraphStateAdapters.toMap(graphState));
        state.put(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, descriptor.agentId());
        try {
            CompiledGraph compiledGraph = officialSingleAgentGraphHolder.compiledGraph();
            OverAllState output;
            if (Boolean.TRUE.equals(request.stream())) {
                AtomicReference<OverAllState> latestState = new AtomicReference<>();
                compiledGraph.stream(
                                state,
                                migrationFacade.runnableConfig(graphState.sessionId(), graphState.taskId()))
                        .doOnNext(nodeOutput -> {
                            latestState.set(nodeOutput.state());
                            sessionStreamService.publish(new SessionStreamEvent(
                                    graphState.sessionId(),
                                    graphState.taskId(),
                                    descriptor.agentId(),
                                    "agent.progress",
                                    "Local child graph node completed",
                                    Map.of(
                                            "phase", "local_child_graph",
                                            "node", nodeOutput.node(),
                                            "agent", nodeOutput.agent() == null ? "" : nodeOutput.agent(),
                                            "visibility", "progress",
                                            "terminal", false
                                    ),
                                    graphState.traceId(),
                                    System.currentTimeMillis()
                            ));
                        })
                        .blockLast();
                output = latestState.get();
                if (output == null) {
                    throw new IllegalStateException("Official single agent graph stream returned empty state");
                }
            } else {
                output = compiledGraph.invoke(
                        state,
                        migrationFacade.runnableConfig(graphState.sessionId(), graphState.taskId())
                ).orElseThrow(() -> new IllegalStateException("Official single agent graph returned empty state"));
            }
            AgentTaskInvokeResponse response = OfficialGraphStateAdapters.latestResponse(output);
            SupervisorWorkflowState workflowState = OfficialGraphStateAdapters.toWorkflowState(output);
            graphAuditService.markCompleted(
                    graphState.sessionId(),
                    graphState.taskId(),
                    graphState.traceId(),
                    SUBGRAPH_NAME,
                    startedAt,
                    Map.of("selectedAgentId", descriptor.agentId(), "domain", graphState.domain())
            );
            return new ExecutionResult(response, workflowState);
        } catch (Exception exception) {
            graphAuditService.markFailed(
                    graphState.sessionId(),
                    graphState.taskId(),
                    graphState.traceId(),
                    SUBGRAPH_NAME,
                    startedAt,
                    exception,
                    Map.of("selectedAgentId", descriptor.agentId(), "domain", graphState.domain())
            );
            throw exception;
        }
    }

    public record ExecutionResult(AgentTaskInvokeResponse response,
                                  SupervisorWorkflowState workflowState) {
    }
}
