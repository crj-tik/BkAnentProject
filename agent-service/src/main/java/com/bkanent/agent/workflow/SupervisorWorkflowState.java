package com.bkanent.agent.workflow;

import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.ApprovalDecision;
import com.bkanent.common.agent.ApprovalRequest;
import com.bkanent.common.agent.WorkflowStatus;

import java.util.List;
import java.util.Map;

public record SupervisorWorkflowState(
        String sessionId,
        String taskId,
        String traceId,
        String userId,
        String userMessage,
        WorkflowStatus workflowStatus,
        String selectedAgentId,
        Map<String, Object> sharedContext,
        List<Map<String, Object>> handoffHistory,
        List<String> artifactIds,
        AgentTaskInvokeResponse latestAgentResponse,
        ApprovalRequest pendingApproval,
        ApprovalDecision latestApprovalDecision,
        String finalAnswer
) {

    public SupervisorWorkflowState withWorkflowStatus(WorkflowStatus status) {
        return new SupervisorWorkflowState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                status,
                selectedAgentId,
                sharedContext,
                handoffHistory,
                artifactIds,
                latestAgentResponse,
                pendingApproval,
                latestApprovalDecision,
                finalAnswer
        );
    }

    public SupervisorWorkflowState withPendingApproval(ApprovalRequest approvalRequest) {
        return new SupervisorWorkflowState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                workflowStatus,
                selectedAgentId,
                sharedContext,
                handoffHistory,
                artifactIds,
                latestAgentResponse,
                approvalRequest,
                latestApprovalDecision,
                finalAnswer
        );
    }

    public SupervisorWorkflowState withLatestApprovalDecision(ApprovalDecision approvalDecision) {
        return new SupervisorWorkflowState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                workflowStatus,
                selectedAgentId,
                sharedContext,
                handoffHistory,
                artifactIds,
                latestAgentResponse,
                pendingApproval,
                approvalDecision,
                finalAnswer
        );
    }

    public SupervisorWorkflowState withAgentResponse(AgentTaskInvokeResponse response) {
        return new SupervisorWorkflowState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                workflowStatus,
                selectedAgentId,
                sharedContext,
                handoffHistory,
                response == null ? artifactIds : response.artifactIds(),
                response,
                pendingApproval,
                latestApprovalDecision,
                finalAnswer
        );
    }

    public SupervisorWorkflowState withFinalAnswer(String answer) {
        return new SupervisorWorkflowState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                workflowStatus,
                selectedAgentId,
                sharedContext,
                handoffHistory,
                artifactIds,
                latestAgentResponse,
                pendingApproval,
                latestApprovalDecision,
                answer
        );
    }

    public SupervisorWorkflowState withHandoffHistory(List<Map<String, Object>> history) {
        return new SupervisorWorkflowState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                workflowStatus,
                selectedAgentId,
                sharedContext,
                history,
                artifactIds,
                latestAgentResponse,
                pendingApproval,
                latestApprovalDecision,
                finalAnswer
        );
    }

    public SupervisorWorkflowState withSharedContext(Map<String, Object> context) {
        return new SupervisorWorkflowState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                workflowStatus,
                selectedAgentId,
                context,
                handoffHistory,
                artifactIds,
                latestAgentResponse,
                pendingApproval,
                latestApprovalDecision,
                finalAnswer
        );
    }
}
