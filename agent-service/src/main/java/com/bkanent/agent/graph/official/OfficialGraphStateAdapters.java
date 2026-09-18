package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.ApprovalDecision;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.agent.WorkflowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OfficialGraphStateAdapters {

    private OfficialGraphStateAdapters() {
    }

    public static SupervisorGraphState toSupervisorGraphState(OverAllState state) {
        return new SupervisorGraphState(
                state.value(OfficialSupervisorGraphKeys.SESSION_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TASK_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TRACE_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.USER_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.USER_MESSAGE, (String) null),
                com.bkanent.common.agent.WorkflowStatus.valueOf(
                        state.value(OfficialSupervisorGraphKeys.WORKFLOW_STATUS,
                                com.bkanent.common.agent.WorkflowStatus.RUNNING.name())),
                castMap(state.value(OfficialSupervisorGraphKeys.SHARED_CONTEXT, Map.of())),
                state.value(OfficialSupervisorGraphKeys.INTENT, (String) null),
                state.value(OfficialSupervisorGraphKeys.DOMAIN, (String) null),
                state.value(OfficialSupervisorGraphKeys.WORKFLOW_TYPE, (String) null),
                state.value(OfficialSupervisorGraphKeys.REQUIRE_PARALLEL, false),
                state.value(OfficialSupervisorGraphKeys.REQUIRE_APPROVAL, false),
                state.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, (String) null),
                castList(state.value(OfficialSupervisorGraphKeys.PARALLEL_DOMAINS, List.of())),
                castList(state.value(OfficialSupervisorGraphKeys.ARTIFACT_IDS, List.of())),
                castHistory(state.value(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, List.of())),
                state.value(OfficialSupervisorGraphKeys.FINAL_ANSWER, (String) null)
        );
    }

    public static Map<String, Object> toMap(SupervisorGraphState state) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put(OfficialSupervisorGraphKeys.SESSION_ID, state.sessionId());
        mapped.put(OfficialSupervisorGraphKeys.TASK_ID, state.taskId());
        mapped.put(OfficialSupervisorGraphKeys.TRACE_ID, state.traceId());
        mapped.put(OfficialSupervisorGraphKeys.USER_ID, state.userId());
        mapped.put(OfficialSupervisorGraphKeys.USER_MESSAGE, state.userMessage());
        mapped.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, state.workflowStatus().name());
        mapped.put(OfficialSupervisorGraphKeys.SHARED_CONTEXT, state.sharedContext());
        mapped.put(OfficialSupervisorGraphKeys.INTENT, state.intent());
        mapped.put(OfficialSupervisorGraphKeys.DOMAIN, state.domain());
        mapped.put(OfficialSupervisorGraphKeys.WORKFLOW_TYPE, state.workflowType());
        mapped.put(OfficialSupervisorGraphKeys.REQUIRE_PARALLEL, state.requireParallel());
        mapped.put(OfficialSupervisorGraphKeys.REQUIRE_APPROVAL, state.requireApproval());
        mapped.put(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, state.selectedAgentId());
        mapped.put(OfficialSupervisorGraphKeys.PARALLEL_DOMAINS, state.parallelDomains());
        mapped.put(OfficialSupervisorGraphKeys.ARTIFACT_IDS, state.artifactIds());
        mapped.put(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, state.handoffHistory());
        mapped.put(OfficialSupervisorGraphKeys.FINAL_ANSWER, state.finalAnswer());
        return mapped;
    }

    /**
     * Converts a graph result to an update map without re-appending collection
     * values that were already present in the parent graph state.
     */
    public static Map<String, Object> toDeltaMap(SupervisorGraphState previous,
                                                  SupervisorGraphState next) {
        Map<String, Object> mapped = toMap(next);
        mapped.put(OfficialSupervisorGraphKeys.ARTIFACT_IDS,
                delta(previous.artifactIds(), next.artifactIds()));
        mapped.put(OfficialSupervisorGraphKeys.HANDOFF_HISTORY,
                delta(previous.handoffHistory(), next.handoffHistory()));
        return mapped;
    }

    /**
     * Converts a workflow result to an update map using append-only deltas for
     * artifact and handoff history keys.
     */
    public static Map<String, Object> toDeltaMap(SupervisorWorkflowState previous,
                                                  SupervisorWorkflowState next) {
        Map<String, Object> mapped = toMap(next);
        mapped.put(OfficialSupervisorGraphKeys.ARTIFACT_IDS,
                delta(previous.artifactIds(), next.artifactIds()));
        mapped.put(OfficialSupervisorGraphKeys.HANDOFF_HISTORY,
                delta(previous.handoffHistory(), next.handoffHistory()));
        return mapped;
    }

    public static <T> List<T> delta(List<T> previous, List<T> next) {
        if (next == null || next.isEmpty()) {
            return List.of();
        }
        List<T> existing = previous == null ? List.of() : previous;
        return next.stream()
                .filter(value -> !existing.contains(value))
                .toList();
    }

    public static Map<String, Object> toMap(SupervisorWorkflowState state) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put(OfficialSupervisorGraphKeys.SESSION_ID, state.sessionId());
        mapped.put(OfficialSupervisorGraphKeys.TASK_ID, state.taskId());
        mapped.put(OfficialSupervisorGraphKeys.TRACE_ID, state.traceId());
        mapped.put(OfficialSupervisorGraphKeys.USER_ID, state.userId());
        mapped.put(OfficialSupervisorGraphKeys.USER_MESSAGE, state.userMessage());
        mapped.put(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, state.workflowStatus().name());
        mapped.put(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, state.selectedAgentId());
        mapped.put(OfficialSupervisorGraphKeys.SHARED_CONTEXT, state.sharedContext());
        mapped.put(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, state.handoffHistory());
        mapped.put(OfficialSupervisorGraphKeys.ARTIFACT_IDS, state.artifactIds());
        mapped.put(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, state.latestAgentResponse());
        mapped.put(OfficialSupervisorGraphKeys.PENDING_APPROVAL, state.pendingApproval());
        mapped.put(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, state.latestApprovalDecision());
        mapped.put(OfficialSupervisorGraphKeys.FINAL_ANSWER, state.finalAnswer());
        return mapped;
    }

    public static SupervisorWorkflowState toWorkflowState(OverAllState state) {
        return new SupervisorWorkflowState(
                state.value(OfficialSupervisorGraphKeys.SESSION_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TASK_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TRACE_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.USER_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.USER_MESSAGE, (String) null),
                WorkflowStatus.valueOf(state.value(
                        OfficialSupervisorGraphKeys.WORKFLOW_STATUS,
                        WorkflowStatus.RUNNING.name()
                )),
                state.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, (String) null),
                castMap(state.value(OfficialSupervisorGraphKeys.SHARED_CONTEXT, Map.of())),
                castHistory(state.value(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, List.of())),
                castList(state.value(OfficialSupervisorGraphKeys.ARTIFACT_IDS, List.of())),
                state.value(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, AgentTaskInvokeResponse.class).orElse(null),
                state.value(OfficialSupervisorGraphKeys.PENDING_APPROVAL, ApprovalRequest.class).orElse(null),
                state.value(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, ApprovalDecision.class).orElse(null),
                state.value(OfficialSupervisorGraphKeys.FINAL_ANSWER, (String) null)
        );
    }

    /**
     * Rehydrates records loaded from the database checkpoint envelope. Jackson
     * stores nested records as maps, while an in-process graph stores the record
     * instances directly, so both representations must be accepted here.
     */
    public static SupervisorWorkflowState toWorkflowState(OverAllState state,
                                                           ObjectMapper objectMapper) {
        return new SupervisorWorkflowState(
                state.value(OfficialSupervisorGraphKeys.SESSION_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TASK_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.TRACE_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.USER_ID, (String) null),
                state.value(OfficialSupervisorGraphKeys.USER_MESSAGE, (String) null),
                WorkflowStatus.valueOf(state.value(
                        OfficialSupervisorGraphKeys.WORKFLOW_STATUS,
                        WorkflowStatus.RUNNING.name()
                )),
                state.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, (String) null),
                castMap(state.value(OfficialSupervisorGraphKeys.SHARED_CONTEXT, Map.of())),
                castHistory(state.value(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, List.of())),
                castList(state.value(OfficialSupervisorGraphKeys.ARTIFACT_IDS, List.of())),
                convert(state.value(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, Object.class).orElse(null),
                        AgentTaskInvokeResponse.class, objectMapper),
                convert(state.value(OfficialSupervisorGraphKeys.PENDING_APPROVAL, Object.class).orElse(null),
                        ApprovalRequest.class, objectMapper),
                convert(state.value(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, Object.class).orElse(null),
                        ApprovalDecision.class, objectMapper),
                state.value(OfficialSupervisorGraphKeys.FINAL_ANSWER, (String) null)
        );
    }

    public static AgentTaskInvokeResponse latestResponse(OverAllState state) {
        return state.value(OfficialSupervisorGraphKeys.LATEST_AGENT_RESPONSE, AgentTaskInvokeResponse.class).orElse(null);
    }

    public static AgentTaskInvokeRequest currentInvokeRequest(OverAllState state) {
        return state.value(OfficialSupervisorGraphKeys.CURRENT_INVOKE_REQUEST, AgentTaskInvokeRequest.class).orElse(null);
    }

    public static Map<String, Object> sharedContext(OverAllState state) {
        return castMap(state.value(OfficialSupervisorGraphKeys.SHARED_CONTEXT, Map.of()));
    }

    public static SupervisorTaskResponse supervisorResponse(OverAllState state) {
        return state.value(OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE, SupervisorTaskResponse.class).orElse(null);
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

    private static <T> T convert(Object value, Class<T> type, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return objectMapper.convertValue(value, type);
    }
}
