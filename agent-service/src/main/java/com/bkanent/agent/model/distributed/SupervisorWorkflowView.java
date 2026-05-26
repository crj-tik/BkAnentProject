package com.bkanent.agent.model.distributed;

import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.ApprovalDecision;
import com.bkanent.common.agent.ApprovalRequest;

import java.util.List;
import java.util.Map;

public record SupervisorWorkflowView(
        String sessionId,
        String taskId,
        String traceId,
        String workflowStatus,
        String selectedAgentId,
        List<Map<String, Object>> handoffHistory,
        List<String> artifactIds,
        ApprovalRequest pendingApproval,
        ApprovalDecision latestApprovalDecision,
        AgentTaskInvokeResponse latestAgentResponse,
        String finalAnswer
) {
}
