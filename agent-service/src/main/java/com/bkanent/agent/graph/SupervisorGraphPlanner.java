package com.bkanent.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bkanent.agent.graph.node.LoadSessionNode;
import com.bkanent.agent.graph.node.ParseIntentNode;
import com.bkanent.agent.graph.node.PlanTaskNode;
import com.bkanent.agent.graph.node.SelectAgentNode;
import com.bkanent.agent.graph.official.OfficialGraphStateAdapters;
import com.bkanent.agent.graph.official.OfficialPlanningGraphHolder;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphMigrationFacade;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SupervisorGraphPlanner {

    private final OfficialPlanningGraphHolder officialPlanningGraphHolder;
    private final OfficialSupervisorGraphMigrationFacade migrationFacade;

    public SupervisorGraphPlanner(LoadSessionNode loadSessionNode,
                                  ParseIntentNode parseIntentNode,
                                  PlanTaskNode planTaskNode,
                                  SelectAgentNode selectAgentNode,
                                  OfficialPlanningGraphHolder officialPlanningGraphHolder,
                                  OfficialSupervisorGraphMigrationFacade migrationFacade) {
        this.officialPlanningGraphHolder = officialPlanningGraphHolder;
        this.migrationFacade = migrationFacade;
    }

    public SupervisorGraphState plan(SupervisorTaskRequest request,
                                     String sessionId,
                                     String taskId,
                                     String traceId) {
        Map<String, Object> initialState = new LinkedHashMap<>(
                migrationFacade.initializeState(request, sessionId, taskId, traceId)
        );
        CompiledGraph compiledGraph = officialPlanningGraphHolder.compiledGraph();
        OverAllState state = compiledGraph.invoke(initialState, migrationFacade.runnableConfig(sessionId, taskId))
                .orElseThrow(() -> new IllegalStateException("Official planning graph returned empty state"));
        return OfficialGraphStateAdapters.toSupervisorGraphState(state);
    }
}
