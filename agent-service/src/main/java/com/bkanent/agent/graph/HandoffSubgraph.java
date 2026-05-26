package com.bkanent.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bkanent.agent.graph.official.OfficialGraphStateAdapters;
import com.bkanent.agent.graph.official.OfficialHandoffGraphHolder;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphKeys;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphMigrationFacade;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HandoffSubgraph {

    private static final String SUBGRAPH_NAME = "handoff";

    private final OfficialHandoffGraphHolder officialHandoffGraphHolder;
    private final OfficialSupervisorGraphMigrationFacade migrationFacade;
    private final GraphAuditService graphAuditService;

    public HandoffSubgraph(OfficialHandoffGraphHolder officialHandoffGraphHolder,
                           OfficialSupervisorGraphMigrationFacade migrationFacade,
                           GraphAuditService graphAuditService) {
        this.officialHandoffGraphHolder = officialHandoffGraphHolder;
        this.migrationFacade = migrationFacade;
        this.graphAuditService = graphAuditService;
    }

    public SupervisorWorkflowState execute(SupervisorWorkflowState state,
                                           String nextDomain,
                                           Map<String, Object> context,
                                           String handoffType) {
        long startedAt = graphAuditService.markStart(
                state.sessionId(),
                state.taskId(),
                state.traceId(),
                SUBGRAPH_NAME,
                Map.of("nextDomain", nextDomain, "handoffType", handoffType)
        );
        Map<String, Object> graphState = new LinkedHashMap<>(OfficialGraphStateAdapters.toMap(state));
        graphState.put(OfficialSupervisorGraphKeys.SHARED_CONTEXT, context);
        graphState.put(OfficialSupervisorGraphKeys.NEXT_DOMAIN, nextDomain);
        graphState.put(OfficialSupervisorGraphKeys.HANDOFF_TYPE, handoffType);
        try {
            CompiledGraph compiledGraph = officialHandoffGraphHolder.compiledGraph();
            OverAllState output = compiledGraph.invoke(
                    graphState,
                    migrationFacade.runnableConfig(state.sessionId(), state.taskId())
            ).orElseThrow(() -> new IllegalStateException("Official handoff graph returned empty state"));
            graphAuditService.markCompleted(
                    state.sessionId(),
                    state.taskId(),
                    state.traceId(),
                    SUBGRAPH_NAME,
                    startedAt,
                    Map.of("nextDomain", nextDomain, "handoffType", handoffType)
            );
            return OfficialGraphStateAdapters.toWorkflowState(output);
        } catch (Exception exception) {
            graphAuditService.markFailed(
                    state.sessionId(),
                    state.taskId(),
                    state.traceId(),
                    SUBGRAPH_NAME,
                    startedAt,
                    exception,
                    Map.of("nextDomain", nextDomain, "handoffType", handoffType)
            );
            throw exception;
        }
    }
}
