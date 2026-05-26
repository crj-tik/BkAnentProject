package com.bkanent.agent.graph;

import com.bkanent.common.agent.WorkflowStatus;

import java.util.List;
import java.util.Map;

public record SupervisorGraphState(
        String sessionId,
        String taskId,
        String traceId,
        String userId,
        String userMessage,
        WorkflowStatus workflowStatus,
        Map<String, Object> sharedContext,
        String intent,
        String domain,
        String workflowType,
        Boolean requireParallel,
        Boolean requireApproval,
        String selectedAgentId,
        List<String> parallelDomains,
        List<String> artifactIds,
        List<Map<String, Object>> handoffHistory,
        String finalAnswer
) {

    public SupervisorGraphState withSharedContext(Map<String, Object> context) {
        return new SupervisorGraphState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                workflowStatus,
                context,
                intent,
                domain,
                workflowType,
                requireParallel,
                requireApproval,
                selectedAgentId,
                parallelDomains,
                artifactIds,
                handoffHistory,
                finalAnswer
        );
    }

    public SupervisorGraphState withIntent(String nextIntent, String nextDomain, String nextWorkflowType) {
        return new SupervisorGraphState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                workflowStatus,
                sharedContext,
                nextIntent,
                nextDomain,
                nextWorkflowType,
                requireParallel,
                requireApproval,
                selectedAgentId,
                parallelDomains,
                artifactIds,
                handoffHistory,
                finalAnswer
        );
    }

    public SupervisorGraphState withPlan(Boolean nextRequireParallel,
                                         Boolean nextRequireApproval,
                                         List<String> nextParallelDomains) {
        return new SupervisorGraphState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                workflowStatus,
                sharedContext,
                intent,
                domain,
                workflowType,
                nextRequireParallel,
                nextRequireApproval,
                selectedAgentId,
                nextParallelDomains,
                artifactIds,
                handoffHistory,
                finalAnswer
        );
    }

    public SupervisorGraphState withSelectedAgent(String nextSelectedAgentId) {
        return new SupervisorGraphState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                workflowStatus,
                sharedContext,
                intent,
                domain,
                workflowType,
                requireParallel,
                requireApproval,
                nextSelectedAgentId,
                parallelDomains,
                artifactIds,
                handoffHistory,
                finalAnswer
        );
    }

    public static SupervisorGraphState initialize(String sessionId,
                                                  String taskId,
                                                  String traceId,
                                                  String userId,
                                                  String userMessage) {
        return new SupervisorGraphState(
                sessionId,
                taskId,
                traceId,
                userId,
                userMessage,
                WorkflowStatus.RUNNING,
                Map.of(),
                null,
                null,
                null,
                false,
                false,
                null,
                List.of(),
                List.of(),
                List.of(),
                null
        );
    }
}
