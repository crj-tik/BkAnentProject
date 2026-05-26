package com.bkanent.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bkanent.agent.graph.official.OfficialGraphStateAdapters;
import com.bkanent.agent.graph.official.OfficialParallelGraphHolder;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphMigrationFacade;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphKeys;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ParallelAgentSubgraph {

    private static final String SUBGRAPH_NAME = "parallel_agent";

    private final OfficialParallelGraphHolder officialParallelGraphHolder;
    private final OfficialSupervisorGraphMigrationFacade migrationFacade;
    private final GraphAuditService graphAuditService;

    public ParallelAgentSubgraph(OfficialParallelGraphHolder officialParallelGraphHolder,
                                 OfficialSupervisorGraphMigrationFacade migrationFacade,
                                 GraphAuditService graphAuditService) {
        this.officialParallelGraphHolder = officialParallelGraphHolder;
        this.migrationFacade = migrationFacade;
        this.graphAuditService = graphAuditService;
    }

    public ExecutionResult execute(SupervisorTaskRequest request, SupervisorGraphState graphState) {
        long startedAt = graphAuditService.markStart(
                graphState.sessionId(),
                graphState.taskId(),
                graphState.traceId(),
                SUBGRAPH_NAME,
                Map.of("parallelDomains", graphState.parallelDomains())
        );
        Map<String, Object> state = new LinkedHashMap<>(OfficialGraphStateAdapters.toMap(graphState));
        state.put(OfficialSupervisorGraphKeys.REQUEST_STREAM, Boolean.TRUE.equals(request.stream()));
        try {
            CompiledGraph compiledGraph = officialParallelGraphHolder.compiledGraph();
            OverAllState output = compiledGraph.invoke(
                    state,
                    migrationFacade.runnableConfig(graphState.sessionId(), graphState.taskId())
            ).orElseThrow(() -> new IllegalStateException("Official parallel graph returned empty state"));
            AgentTaskInvokeResponse response = OfficialGraphStateAdapters.latestResponse(output);
            SupervisorWorkflowState workflowState = OfficialGraphStateAdapters.toWorkflowState(output);
            graphAuditService.markCompleted(
                    graphState.sessionId(),
                    graphState.taskId(),
                    graphState.traceId(),
                    SUBGRAPH_NAME,
                    startedAt,
                    Map.of("parallelDomains", graphState.parallelDomains())
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
                    Map.of("parallelDomains", graphState.parallelDomains())
            );
            throw exception;
        }
    }

    public record ExecutionResult(AgentTaskInvokeResponse response,
                                  SupervisorWorkflowState workflowState) {
    }
}
