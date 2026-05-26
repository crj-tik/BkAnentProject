package com.bkanent.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.bkanent.agent.graph.official.OfficialApprovalDecisionGraphHolder;
import com.bkanent.agent.graph.official.OfficialApprovalGraphHolder;
import com.bkanent.agent.graph.official.OfficialGraphStateAdapters;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphKeys;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphMigrationFacade;
import com.bkanent.agent.graph.node.PersistSessionNode;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.agent.workflow.GraphCheckpointStore;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.ApprovalCallbackRequest;
import com.bkanent.common.agent.ApprovalDecision;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class ApprovalSubgraphService {

    private final GraphCheckpointStore checkpointStore;
    private final PersistSessionNode persistSessionNode;
    private final SessionStreamService sessionStreamService;
    private final OfficialApprovalGraphHolder officialApprovalGraphHolder;
    private final OfficialApprovalDecisionGraphHolder officialApprovalDecisionGraphHolder;
    private final OfficialSupervisorGraphMigrationFacade migrationFacade;

    public ApprovalSubgraphService(GraphCheckpointStore checkpointStore,
                                   PersistSessionNode persistSessionNode,
                                   SessionStreamService sessionStreamService,
                                   OfficialApprovalGraphHolder officialApprovalGraphHolder,
                                   OfficialApprovalDecisionGraphHolder officialApprovalDecisionGraphHolder,
                                   OfficialSupervisorGraphMigrationFacade migrationFacade) {
        this.checkpointStore = checkpointStore;
        this.persistSessionNode = persistSessionNode;
        this.sessionStreamService = sessionStreamService;
        this.officialApprovalGraphHolder = officialApprovalGraphHolder;
        this.officialApprovalDecisionGraphHolder = officialApprovalDecisionGraphHolder;
        this.migrationFacade = migrationFacade;
    }

    public SupervisorTaskResponse enterWaitingState(SupervisorWorkflowState state,
                                                    ApprovalRequest approvalRequest,
                                                    Map<String, Object> metadata) {
        SupervisorWorkflowState waitingState = invokeOfficialWaitingGraph(state, approvalRequest);
        checkpointStore.save(waitingState);
        persistSessionNode.persist(waitingState, approvalRequest.summary());
        publish(waitingState.sessionId(), waitingState.taskId(), waitingState.selectedAgentId(),
                "task.waiting_approval", approvalRequest.summary(), withUserId(metadata, waitingState.userId()), waitingState.traceId());
        return buildResponse(waitingState, approvalRequest.summary());
    }

    public ApprovalDecision receiveDecision(SupervisorWorkflowState state, ApprovalCallbackRequest request) {
        ApprovalDecision decision = new ApprovalDecision(
                request.approvalId(),
                request.status(),
                request.reviewerId(),
                request.feedback(),
                LocalDateTime.now(),
                request.traceId()
        );
        publish(state.sessionId(), state.taskId(), state.selectedAgentId(),
                "task.approval_received", request.status().name(),
                Map.of("approvalId", request.approvalId(), "feedback", blankToEmpty(request.feedback()), "userId", blankToEmpty(state.userId())),
                state.traceId());
        publish(state.sessionId(), state.taskId(), state.selectedAgentId(),
                approvalStatusEvent(request.status().name()),
                request.status().name(),
                Map.of("approvalId", request.approvalId(), "userId", blankToEmpty(state.userId())),
                state.traceId());
        return decision;
    }

    public DecisionTransition applyDecisionTransition(SupervisorWorkflowState state, ApprovalDecision decision) {
        try {
            CompiledGraph compiledGraph = officialApprovalDecisionGraphHolder.compiledGraph();
            Map<String, Object> graphState = new java.util.LinkedHashMap<>(OfficialGraphStateAdapters.toMap(state));
            graphState.put(OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION, decision);
            OverAllState output = compiledGraph.invoke(
                    graphState,
                    migrationFacade.runnableConfig(state.sessionId(), state.taskId())
            ).orElseThrow(() -> new IllegalStateException("Official approval decision graph returned empty state"));
            return new DecisionTransition(
                    OfficialGraphStateAdapters.toWorkflowState(output),
                    output.value(OfficialSupervisorGraphKeys.APPROVAL_RESUME_ACTION, "complete")
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to apply approval decision by official graph", exception);
        }
    }

    public SupervisorTaskResponse failRetryLimit(SupervisorWorkflowState state, int nextRetry) {
        checkpointStore.delete(state.taskId());
        SupervisorWorkflowState failed = state.withWorkflowStatus(WorkflowStatus.FAILED)
                .withFinalAnswer("Approval rejection exceeded the retry limit");
        persistSessionNode.persist(failed, failed.finalAnswer());
        publish(failed.sessionId(), failed.taskId(), failed.selectedAgentId(),
                "task.failed", failed.finalAnswer(), Map.of(
                        "retryCount", nextRetry,
                        "userId", blankToEmpty(failed.userId()),
                        "approvalId", failed.pendingApproval() == null ? "" : blankToEmpty(failed.pendingApproval().approvalId())
                ), failed.traceId());
        return buildResponse(failed, failed.finalAnswer());
    }

    public SupervisorTaskResponse terminate(SupervisorWorkflowState state, String feedback) {
        String finalAnswer = StringUtils.hasText(feedback) ? feedback : "Workflow terminated";
        SupervisorWorkflowState canceled = state.withWorkflowStatus(WorkflowStatus.CANCELED).withFinalAnswer(finalAnswer);
        persistSessionNode.persist(canceled, finalAnswer);
        checkpointStore.delete(state.taskId());
        publish(canceled.sessionId(), canceled.taskId(), canceled.selectedAgentId(),
                "task.failed", finalAnswer, Map.of(
                        "status", WorkflowStatus.CANCELED.name(),
                        "userId", blankToEmpty(canceled.userId()),
                        "approvalId", canceled.pendingApproval() == null ? "" : blankToEmpty(canceled.pendingApproval().approvalId())
                ), canceled.traceId());
        return buildResponse(canceled, finalAnswer);
    }

    private String approvalStatusEvent(String status) {
        return switch (status) {
            case "APPROVED" -> "task.approval_approved";
            case "REJECTED" -> "task.approval_rejected";
            case "TERMINATED" -> "task.approval_terminated";
            default -> "task.approval_received";
        };
    }

    private void publish(String sessionId,
                         String taskId,
                         String agentId,
                         String eventType,
                         String content,
                         Map<String, Object> metadata,
                         String traceId) {
        sessionStreamService.publish(new SessionStreamEvent(
                sessionId,
                taskId,
                agentId,
                eventType,
                content,
                metadata == null ? Map.of() : metadata,
                traceId,
                System.currentTimeMillis()
        ));
    }

    private SupervisorTaskResponse buildResponse(SupervisorWorkflowState state, String answer) {
        return new SupervisorTaskResponse(
                state.sessionId(),
                state.taskId(),
                state.workflowStatus().name(),
                answer,
                state.artifactIds(),
                state.traceId(),
                state.selectedAgentId(),
                Map.of()
        );
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> withUserId(Map<String, Object> metadata, String userId) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        merged.put("userId", blankToEmpty(userId));
        merged.putIfAbsent("stage", "approval");
        return Map.copyOf(merged);
    }

    private SupervisorWorkflowState invokeOfficialWaitingGraph(SupervisorWorkflowState state,
                                                               ApprovalRequest approvalRequest) {
        try {
            CompiledGraph compiledGraph = officialApprovalGraphHolder.compiledGraph();
            Map<String, Object> graphState = new java.util.LinkedHashMap<>(OfficialGraphStateAdapters.toMap(state));
            graphState.put(OfficialSupervisorGraphKeys.PENDING_APPROVAL, approvalRequest);
            OverAllState output = compiledGraph.invoke(
                    graphState,
                    migrationFacade.runnableConfig(state.sessionId(), state.taskId())
            ).orElseThrow(() -> new IllegalStateException("Official approval graph returned empty state"));
            return OfficialGraphStateAdapters.toWorkflowState(output);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to enter waiting approval state by official graph", exception);
        }
    }

    public record DecisionTransition(SupervisorWorkflowState state, String resumeAction) {
    }
}
