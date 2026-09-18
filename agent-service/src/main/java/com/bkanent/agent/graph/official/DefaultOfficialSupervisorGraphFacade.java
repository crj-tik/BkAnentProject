package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.ApprovalCallbackRequest;
import com.bkanent.common.agent.ApprovalDecision;
import com.bkanent.common.agent.ApprovalStatus;
import com.bkanent.common.agent.WorkflowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

@Component
public class DefaultOfficialSupervisorGraphFacade implements OfficialSupervisorGraphFacade {

    private static final ConcurrentMap<String, Object> RESUME_LOCKS = new ConcurrentHashMap<>();

    private final OfficialSupervisorGraphHolder graphHolder;
    private final OfficialSupervisorGraphMigrationFacade migrationFacade;
    private final ApprovalResumeClaimStore approvalResumeClaimStore;
    private final ObjectMapper objectMapper;

    public DefaultOfficialSupervisorGraphFacade(OfficialSupervisorGraphHolder graphHolder,
                                                OfficialSupervisorGraphMigrationFacade migrationFacade,
                                                ApprovalResumeClaimStore approvalResumeClaimStore,
                                                ObjectMapper objectMapper) {
        this.graphHolder = graphHolder;
        this.migrationFacade = migrationFacade;
        this.approvalResumeClaimStore = approvalResumeClaimStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public SupervisorTaskResponse execute(SupervisorTaskRequest request) {
        String sessionId = StringUtils.hasText(request.sessionId())
                ? request.sessionId() : UUID.randomUUID().toString();
        String taskId = StringUtils.hasText(request.requestId())
                ? request.requestId() : UUID.randomUUID().toString();
        String traceId = StringUtils.hasText(request.traceId()) ? request.traceId() : taskId;
        SupervisorTaskRequest normalized = new SupervisorTaskRequest(
                sessionId,
                request.userId(),
                taskId,
                traceId,
                request.userMessage(),
                request.context(),
                request.channel(),
                request.stream()
        );
        RunnableConfig graphConfig = migrationFacade.runnableConfig(sessionId, taskId);
        StateSnapshot existing = graphHolder.compiledGraph().lastStateOf(graphConfig).orElse(null);
        if (existing != null) {
            // A repeated submission with the same task/thread must not restart
            // planning or invoke a child Agent a second time. Recovery of a
            // non-terminal RUNNING state is handled by the worker/recovery
            // path, while this API remains idempotent.
            return responseOf(existing.state(), normalized);
        }
        Map<String, Object> initialState = migrationFacade.initializeState(
                normalized, sessionId, taskId, traceId);
        OverAllState output = graphHolder.compiledGraph()
                .invoke(initialState, graphConfig)
                .orElseThrow(() -> new IllegalStateException("Official supervisor graph returned empty state"));
        return responseOf(output, normalized);
    }

    @Override
    public SupervisorTaskResponse resume(ApprovalCallbackRequest request) {
        if (request == null || !StringUtils.hasText(request.taskId())
                || !StringUtils.hasText(request.approvalId())) {
            throw new IllegalArgumentException("taskId and approvalId are required");
        }
        String lockKey = StringUtils.hasText(request.taskId()) ? request.taskId() : request.approvalId();
        Object lock = RESUME_LOCKS.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            try {
                return resumeLocked(request);
            } finally {
                RESUME_LOCKS.remove(lockKey, lock);
            }
        }
    }

    private SupervisorTaskResponse resumeLocked(ApprovalCallbackRequest request) {
        if (request.status() == null || request.status() == ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("PENDING is not a valid callback result");
        }
        CompiledGraph graph = graphHolder.compiledGraph();
        RunnableConfig baseConfig = migrationFacade.runnableConfig(request.sessionId(), request.taskId());
        StateSnapshot snapshot = graph.lastStateOf(baseConfig)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No official graph checkpoint for taskId=" + request.taskId()));
        SupervisorWorkflowState current = OfficialGraphStateAdapters.toWorkflowState(
                snapshot.state(), objectMapper);
        validateCallback(current, request);

        if (current.latestApprovalDecision() != null
                && StringUtils.hasText(request.approvalId())
                && request.approvalId().equals(current.latestApprovalDecision().approvalId())) {
            return responseOf(snapshot.state(), null);
        }
        String idempotencyKey = snapshot.state().value(
                OfficialSupervisorGraphKeys.RESUME_IDEMPOTENCY_KEY, (String) null);
        if (StringUtils.hasText(idempotencyKey)
                && idempotencyKey.equals(request.approvalId())) {
            return responseOf(snapshot.state(), null);
        }
        if (current.pendingApproval() == null
                || !request.approvalId().equals(current.pendingApproval().approvalId())) {
            throw new IllegalArgumentException("Approval id does not match pending graph approval");
        }
        if (request.approvalVersion() != null
                && !request.approvalVersion().equals(current.pendingApproval().subjectVersion())) {
            throw new IllegalArgumentException("Approval version does not match pending graph approval");
        }
        if (current.workflowStatus() != WorkflowStatus.WAITING_USER_APPROVAL) {
            throw new IllegalArgumentException("Workflow is not waiting for user approval");
        }
        if (snapshot.config().checkPointId().isEmpty()) {
            throw new IllegalStateException("Official graph checkpoint has no checkpoint id");
        }

        ApprovalResumeClaimStore.ClaimResult claim = approvalResumeClaimStore.claim(request);
        if (!claim.acquired()) {
            return claim.replayedResponse();
        }

        ApprovalDecision decision = new ApprovalDecision(
                request.approvalId(),
                request.status(),
                request.reviewerId(),
                request.feedback(),
                LocalDateTime.now(),
                request.traceId()
        );
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, decision);
        updates.put(OfficialSupervisorGraphKeys.RESUME_FEEDBACK,
                request.feedback() == null ? "" : request.feedback());
        RunnableConfig checkpointConfig = snapshot.config();
        RunnableConfig resumedConfig;
        try {
            resumedConfig = graph.updateState(checkpointConfig, updates).withResume();
        } catch (Exception exception) {
            approvalResumeClaimStore.fail(request.approvalId(), exception);
            throw new IllegalStateException("Failed to update official supervisor checkpoint", exception);
        }
        try {
            OverAllState output = graph.invoke(Map.of(), resumedConfig)
                    .orElseThrow(() -> new IllegalStateException(
                            "Official supervisor graph resume returned empty state"));
            SupervisorTaskResponse response = responseOf(output, null);
            approvalResumeClaimStore.complete(request.approvalId(), response);
            return response;
        } catch (Exception exception) {
            approvalResumeClaimStore.fail(request.approvalId(), exception);
            throw exception;
        }
    }

    private void validateCallback(SupervisorWorkflowState state, ApprovalCallbackRequest request) {
        if (!StringUtils.hasText(state.taskId()) || !StringUtils.hasText(request.taskId())
                || !request.taskId().equals(state.taskId())) {
            throw new IllegalArgumentException("Approval taskId does not match graph thread");
        }
        if (StringUtils.hasText(request.sessionId()) && !request.sessionId().equals(state.sessionId())) {
            throw new IllegalArgumentException("Approval sessionId does not match graph state");
        }
    }

    private SupervisorTaskResponse responseOf(OverAllState state, SupervisorTaskRequest request) {
        Object value = state.value(OfficialSupervisorGraphKeys.SUPERVISOR_RESPONSE, Object.class).orElse(null);
        if (value instanceof SupervisorTaskResponse response) {
            return response;
        }
        if (value != null) {
            return objectMapper.convertValue(value, SupervisorTaskResponse.class);
        }
        String status = state.value(OfficialSupervisorGraphKeys.WORKFLOW_STATUS,
                WorkflowStatus.RUNNING.name());
        String answer = state.value(OfficialSupervisorGraphKeys.FINAL_ANSWER,
                WorkflowStatus.WAITING_USER_APPROVAL.name().equals(status)
                        ? "Workflow is waiting for user approval."
                        : "Workflow is running.");
        return new SupervisorTaskResponse(
                state.value(OfficialSupervisorGraphKeys.SESSION_ID,
                        request == null ? null : request.sessionId()),
                state.value(OfficialSupervisorGraphKeys.TASK_ID,
                        request == null ? null : request.requestId()),
                status,
                answer,
                castList(state.value(OfficialSupervisorGraphKeys.ARTIFACT_IDS, List.of())),
                state.value(OfficialSupervisorGraphKeys.TRACE_ID,
                        request == null ? null : request.traceId()),
                state.value(OfficialSupervisorGraphKeys.SELECTED_AGENT_ID, (String) null),
                Map.of()
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
