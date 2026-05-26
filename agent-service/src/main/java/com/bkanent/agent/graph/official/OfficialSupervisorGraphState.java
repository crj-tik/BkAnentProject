package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.OverAllState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record OfficialSupervisorGraphState(OverAllState delegate) {

    public Optional<String> sessionId() {
        return delegate.value(OfficialSupervisorGraphKeys.SESSION_ID);
    }

    public Optional<String> taskId() {
        return delegate.value(OfficialSupervisorGraphKeys.TASK_ID);
    }

    public Optional<String> traceId() {
        return delegate.value(OfficialSupervisorGraphKeys.TRACE_ID);
    }

    public Optional<String> userId() {
        return delegate.value(OfficialSupervisorGraphKeys.USER_ID);
    }

    public Optional<String> userMessage() {
        return delegate.value(OfficialSupervisorGraphKeys.USER_MESSAGE);
    }

    public Optional<String> workflowStatus() {
        return delegate.value(OfficialSupervisorGraphKeys.WORKFLOW_STATUS);
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> sharedContext() {
        return (Optional<Map<String, Object>>) (Optional<?>) delegate.value(OfficialSupervisorGraphKeys.SHARED_CONTEXT);
    }

    public Optional<String> intent() {
        return delegate.value(OfficialSupervisorGraphKeys.INTENT);
    }

    public Optional<String> domain() {
        return delegate.value(OfficialSupervisorGraphKeys.DOMAIN);
    }

    public Optional<String> workflowType() {
        return delegate.value(OfficialSupervisorGraphKeys.WORKFLOW_TYPE);
    }

    public Optional<Boolean> requireParallel() {
        return delegate.value(OfficialSupervisorGraphKeys.REQUIRE_PARALLEL);
    }

    public Optional<Boolean> requireApproval() {
        return delegate.value(OfficialSupervisorGraphKeys.REQUIRE_APPROVAL);
    }

    public Optional<String> selectedAgentId() {
        return delegate.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID);
    }

    @SuppressWarnings("unchecked")
    public Optional<List<String>> parallelDomains() {
        return (Optional<List<String>>) (Optional<?>) delegate.value(OfficialSupervisorGraphKeys.PARALLEL_DOMAINS);
    }

    @SuppressWarnings("unchecked")
    public Optional<List<String>> artifactIds() {
        return (Optional<List<String>>) (Optional<?>) delegate.value(OfficialSupervisorGraphKeys.ARTIFACT_IDS);
    }

    public static OfficialSupervisorGraphState from(OverAllState state) {
        return new OfficialSupervisorGraphState(state);
    }
}
