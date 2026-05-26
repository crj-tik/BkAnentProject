package com.bkanent.agent.graph.node;

import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.ApprovalRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;

@Component
public class BuildApprovalRequestNode {

    public ApprovalRequest build(SupervisorWorkflowState state, Map<String, Object> context) {
        String summary = textOrDefault(context.get("approvalSummary"), "Task result generated, waiting for approval.");
        String approvalType = textOrDefault(context.get("approvalType"), "generic-review");
        String subjectType = textOrDefault(context.get("subjectType"), "agent-output");
        int maxRetryCount = resolveInt(context, "maxRetryCount", 3);
        String approveNextNode = textOrDefault(context.get("approveNextNode"), "complete");
        return new ApprovalRequest(
                UUID.randomUUID().toString(),
                state.taskId(),
                state.sessionId(),
                approvalType,
                subjectType,
                state.taskId(),
                1,
                "Approval Required",
                summary,
                state.latestAgentResponse() == null ? Map.of() : state.latestAgentResponse().structuredOutput(),
                approveNextNode,
                "regenerate",
                "cancel",
                0,
                maxRetryCount,
                state.traceId()
        );
    }

    private int resolveInt(Map<String, Object> context, String key, int defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        Object value = context.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String textOrDefault(Object value, String defaultValue) {
        return StringUtils.hasText(value == null ? null : String.valueOf(value))
                ? String.valueOf(value)
                : defaultValue;
    }
}
