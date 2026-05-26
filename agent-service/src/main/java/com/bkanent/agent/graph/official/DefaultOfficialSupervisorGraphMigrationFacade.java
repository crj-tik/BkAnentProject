package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultOfficialSupervisorGraphMigrationFacade implements OfficialSupervisorGraphMigrationFacade {

    private final OfficialSupervisorGraphThreadResolver threadResolver;

    public DefaultOfficialSupervisorGraphMigrationFacade(OfficialSupervisorGraphThreadResolver threadResolver) {
        this.threadResolver = threadResolver;
    }

    @Override
    public Map<String, Object> initializeState(SupervisorTaskRequest request,
                                               String sessionId,
                                               String taskId,
                                               String traceId) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(OfficialSupervisorGraphKeys.SESSION_ID, sessionId);
        state.put(OfficialSupervisorGraphKeys.TASK_ID, taskId);
        state.put(OfficialSupervisorGraphKeys.TRACE_ID, traceId);
        state.put(OfficialSupervisorGraphKeys.USER_ID, request.userId());
        state.put(OfficialSupervisorGraphKeys.USER_MESSAGE, request.userMessage());
        state.put(OfficialSupervisorGraphKeys.REQUEST_STREAM, Boolean.TRUE.equals(request.stream()));
        state.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, WorkflowStatus.RUNNING.name());
        state.put(OfficialSupervisorGraphKeys.SHARED_CONTEXT,
                request.context() == null ? Map.of() : Map.copyOf(request.context()));
        state.put(OfficialSupervisorGraphKeys.PARALLEL_DOMAINS, List.of());
        state.put(OfficialSupervisorGraphKeys.ARTIFACT_IDS, List.of());
        state.put(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, List.of());
        return state;
    }

    @Override
    public RunnableConfig runnableConfig(String sessionId, String taskId) {
        return threadResolver.resolve(sessionId, taskId);
    }
}
