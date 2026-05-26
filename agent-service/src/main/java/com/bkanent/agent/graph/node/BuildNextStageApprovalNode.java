package com.bkanent.agent.graph.node;

import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.ApprovalRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;

@Component
public class BuildNextStageApprovalNode {

    public ApprovalRequest build(SupervisorWorkflowState state) {
        Map<String, Object> context = state.sharedContext();
        return new ApprovalRequest(
                UUID.randomUUID().toString(),
                state.taskId(),
                state.sessionId(),
                textOrDefault(context.get("nextApprovalType"), "next-stage-review"),
                textOrDefault(context.get("nextSubjectType"), "agent-output"),
                state.taskId(),
                1,
                "Approval Required",
                textOrDefault(context.get("nextApprovalSummary"), "Next-stage result generated, waiting for approval."),
                state.latestAgentResponse() == null ? Map.of() : state.latestAgentResponse().structuredOutput(),
                "complete",
                "regenerate",
                "cancel",
                0,
                resolveInt(context, "nextMaxRetryCount", 3),
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
