package com.bkanent.agent.model.distributed;

import java.util.List;

public record WorkflowPlan(
        String intent,
        String domain,
        String workflowType,
        Boolean requireApproval,
        List<String> parallelDomains,
        String selectedAgentId,
        List<WorkflowPlanStep> steps,
        String planningModel,
        String rawPlan
) {
}
