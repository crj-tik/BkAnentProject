package com.bkanent.agent.model.distributed;

import java.util.Map;

public record WorkflowPlanStep(
        String type,
        String domain,
        String intent,
        String approvalType,
        Map<String, Object> metadata
) {
}
