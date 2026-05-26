package com.bkanent.agent.service;

import com.bkanent.agent.model.distributed.WorkflowPlan;
import com.bkanent.agent.registry.AgentRegistry;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

@Service
public class WorkflowPlanValidator {

    private static final Set<String> ALLOWED_DOMAINS = Set.of(
            "listing", "marketing", "media", "trade", "contract", "settlement", "notification"
    );

    private static final Set<String> ALLOWED_WORKFLOW_TYPES = Set.of(
            "single_agent", "parallel", "marketing_pipeline",
            "marketing_with_approval", "trade_with_approval",
            "contract_with_approval", "listing_with_approval"
    );

    private final AgentRegistry agentRegistry;

    public WorkflowPlanValidator(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    public WorkflowPlan validate(WorkflowPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("workflow plan is null");
        }
        if (!StringUtils.hasText(plan.domain()) || !ALLOWED_DOMAINS.contains(plan.domain())) {
            throw new IllegalArgumentException("workflow plan domain is invalid");
        }
        if (!StringUtils.hasText(plan.intent())) {
            throw new IllegalArgumentException("workflow plan intent is invalid");
        }
        if (StringUtils.hasText(plan.workflowType()) && !ALLOWED_WORKFLOW_TYPES.contains(plan.workflowType())) {
            throw new IllegalArgumentException("workflow plan workflowType is invalid");
        }
        if (StringUtils.hasText(plan.selectedAgentId())
                && agentRegistry.getByAgentId(plan.selectedAgentId()).isEmpty()) {
            throw new IllegalArgumentException("workflow plan selectedAgentId is not registered");
        }
        List<String> parallelDomains = plan.parallelDomains() == null ? List.of() : plan.parallelDomains();
        for (String domain : parallelDomains) {
            if (!StringUtils.hasText(domain) || !ALLOWED_DOMAINS.contains(domain)) {
                throw new IllegalArgumentException("workflow plan parallelDomains contains invalid domain");
            }
        }
        return plan;
    }
}
