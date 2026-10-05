package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Read-only adapter for historical workflow snapshots exposed by query APIs. */
public final class OfficialGraphStateAdapters {

    private OfficialGraphStateAdapters() {
    }

    public static SupervisorWorkflowState toWorkflowState(OverAllState state, ObjectMapper mapper) {
        return new SupervisorWorkflowState(
                state.value(OfficialSupervisorGraphKeys.SESSION_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TASK_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TRACE_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.USER_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.USER_MESSAGE, (String) null),
                WorkflowStatus.valueOf(state.value(OfficialSupervisorGraphKeys.WORKFLOW_STATUS,
                        WorkflowStatus.RUNNING.name())),
                state.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, (String) null),
                castMap(state.value(OfficialSupervisorGraphKeys.SHARED_CONTEXT, Map.of())),
                castHistory(state.value(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, List.of())),
                castList(state.value(OfficialSupervisorGraphKeys.ARTIFACT_IDS, List.of())),
                convert(state.value(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, Object.class).orElse(null),
                        AgentTaskInvokeResponse.class, mapper),
                convert(state.value(OfficialSupervisorGraphKeys.PENDING_APPROVAL, Object.class).orElse(null),
                        ApprovalRequest.class, mapper),
                convert(state.value(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, Object.class).orElse(null),
                        ApprovalDecision.class, mapper),
                state.value(OfficialSupervisorGraphKeys.FINAL_ANSWER, (String) null)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castHistory(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static <T> T convert(Object value, Class<T> type, ObjectMapper mapper) {
        if (value == null) return null;
        return type.isInstance(value) ? type.cast(value) : mapper.convertValue(value, type);
    }
}
