package com.bkanent.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bkanent.agent.graph.official.OfficialGraphStateAdapters;
import com.bkanent.agent.graph.official.OfficialResumeGraphHolder;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphKeys;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphMigrationFacade;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ResumeSubgraph {

    private static final String SUBGRAPH_NAME = "resume";

    private final OfficialResumeGraphHolder officialResumeGraphHolder;
    private final OfficialSupervisorGraphMigrationFacade migrationFacade;
    private final GraphAuditService graphAuditService;

    public ResumeSubgraph(OfficialResumeGraphHolder officialResumeGraphHolder,
                          OfficialSupervisorGraphMigrationFacade migrationFacade,
                          GraphAuditService graphAuditService) {
        this.officialResumeGraphHolder = officialResumeGraphHolder;
        this.migrationFacade = migrationFacade;
        this.graphAuditService = graphAuditService;
    }

    public SupervisorTaskResponse execute(SupervisorWorkflowState state, String resumeAction, String feedback) {
        long startedAt = graphAuditService.markStart(
                state.sessionId(),
                state.taskId(),
                state.traceId(),
                SUBGRAPH_NAME,
                Map.of("resumeAction", String.valueOf(resumeAction))
        );
        Map<String, Object> graphState = new LinkedHashMap<>(OfficialGraphStateAdapters.toMap(state));
        graphState.put(OfficialSupervisorGraphKeys.APPROVAL_RESUME_ACTION, resumeAction);
        graphState.put(OfficialSupervisorGraphKeys.RESUME_FEEDBACK, feedback);
        try {
            CompiledGraph compiledGraph = officialResumeGraphHolder.compiledGraph();
            OverAllState output = compiledGraph.invoke(
                    graphState,
                    migrationFacade.runnableConfig(state.sessionId(), state.taskId())
            ).orElseThrow(() -> new IllegalStateException("Official resume graph returned empty state"));
            graphAuditService.markCompleted(
                    state.sessionId(),
                    state.taskId(),
                    state.traceId(),
                    SUBGRAPH_NAME,
                    startedAt,
                    Map.of("resumeAction", String.valueOf(resumeAction))
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
                    Map.of("resumeAction", String.valueOf(resumeAction))
            );
            throw exception;
        }
    }
}
