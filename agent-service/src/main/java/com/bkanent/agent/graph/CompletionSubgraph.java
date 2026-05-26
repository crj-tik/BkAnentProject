package com.bkanent.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bkanent.agent.graph.official.OfficialCompletionGraphHolder;
import com.bkanent.agent.graph.official.OfficialGraphStateAdapters;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphMigrationFacade;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CompletionSubgraph {

    private static final String SUBGRAPH_NAME = "completion";

    private final OfficialCompletionGraphHolder officialCompletionGraphHolder;
    private final OfficialSupervisorGraphMigrationFacade migrationFacade;
    private final GraphAuditService graphAuditService;

    public CompletionSubgraph(OfficialCompletionGraphHolder officialCompletionGraphHolder,
                              OfficialSupervisorGraphMigrationFacade migrationFacade,
                              GraphAuditService graphAuditService) {
        this.officialCompletionGraphHolder = officialCompletionGraphHolder;
        this.migrationFacade = migrationFacade;
        this.graphAuditService = graphAuditService;
    }

    public SupervisorTaskResponse execute(SupervisorWorkflowState state) {
        long startedAt = graphAuditService.markStart(
                state.sessionId(),
                state.taskId(),
                state.traceId(),
                SUBGRAPH_NAME,
                Map.of("selectedAgentId", String.valueOf(state.selectedAgentId()))
        );
        Map<String, Object> graphState = new LinkedHashMap<>(OfficialGraphStateAdapters.toMap(state));
        try {
            CompiledGraph compiledGraph = officialCompletionGraphHolder.compiledGraph();
            OverAllState output = compiledGraph.invoke(
                    graphState,
                    migrationFacade.runnableConfig(state.sessionId(), state.taskId())
            ).orElseThrow(() -> new IllegalStateException("Official completion graph returned empty state"));
            graphAuditService.markCompleted(
                    state.sessionId(),
                    state.taskId(),
                    state.traceId(),
                    SUBGRAPH_NAME,
                    startedAt,
                    Map.of("selectedAgentId", String.valueOf(state.selectedAgentId()))
            );
            return OfficialGraphStateAdapters.supervisorResponse(output);
        } catch (Exception exception) {
            graphAuditService.markFailed(
                    state.sessionId(),
                    state.taskId(),
                    state.traceId(),
                    SUBGRAPH_NAME,
                    startedAt,
                    exception,
                    Map.of("selectedAgentId", String.valueOf(state.selectedAgentId()))
            );
            throw exception;
        }
    }
}
