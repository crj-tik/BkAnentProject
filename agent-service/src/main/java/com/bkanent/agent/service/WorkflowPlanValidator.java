package com.bkanent.agent.service;

import com.bkanent.agent.model.distributed.WorkflowPlan;
import com.bkanent.agent.model.distributed.WorkflowPlanStep;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.HashSet;
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
    private static final Set<String> ALLOWED_STEP_TYPES = Set.of(
            "agent", "invoke", "single_agent", "parallel", "approval", "handoff", "complete"
    );
    private static final int MAX_PARALLEL_DOMAINS = 7;
    private static final int MAX_PLAN_STEPS = 16;

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
        List<String> parallelDomains = plan.parallelDomains() == null ? List.of() : plan.parallelDomains();
        if (parallelDomains.size() > MAX_PARALLEL_DOMAINS) {
            throw new IllegalArgumentException("workflow plan parallelDomains exceeds the maximum of "
                    + MAX_PARALLEL_DOMAINS);
        }
        if (new HashSet<>(parallelDomains).size() != parallelDomains.size()) {
            throw new IllegalArgumentException("workflow plan parallelDomains must be distinct");
        }
        for (String domain : parallelDomains) {
            if (!StringUtils.hasText(domain) || !ALLOWED_DOMAINS.contains(domain)) {
                throw new IllegalArgumentException("workflow plan parallelDomains contains invalid domain");
            }
        }
        if ("parallel".equals(plan.workflowType()) && parallelDomains.size() < 2) {
            throw new IllegalArgumentException("parallel workflow plan requires at least two domains");
        }
        if (plan.requireApproval() != null && plan.workflowType() != null
                && plan.workflowType().endsWith("_with_approval")
                && !plan.requireApproval()) {
            throw new IllegalArgumentException("approval workflow plan must require approval");
        }
        validateSteps(plan.steps());
        validateSelectedAgent(plan, plan.selectedAgentId());
        return plan;
    }

    private void validateSteps(List<WorkflowPlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        if (steps.size() > MAX_PLAN_STEPS) {
            throw new IllegalArgumentException("workflow plan steps exceed the maximum of " + MAX_PLAN_STEPS);
        }
        for (WorkflowPlanStep step : steps) {
            if (step == null || !StringUtils.hasText(step.type())
                    || !ALLOWED_STEP_TYPES.contains(step.type())) {
                throw new IllegalArgumentException("workflow plan contains an invalid step type");
            }
            if (StringUtils.hasText(step.domain()) && !ALLOWED_DOMAINS.contains(step.domain())) {
                throw new IllegalArgumentException("workflow plan step contains an invalid domain");
            }
            if (("agent".equals(step.type()) || "invoke".equals(step.type())
                    || "single_agent".equals(step.type())) && !StringUtils.hasText(step.intent())) {
                throw new IllegalArgumentException("agent workflow plan step requires an intent");
            }
        }
    }

    private void validateSelectedAgent(WorkflowPlan plan, String selectedAgentId) {
        if (!StringUtils.hasText(selectedAgentId)) {
            return;
        }
        RegisteredAgentDescriptor descriptor = agentRegistry.getByAgentId(selectedAgentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "workflow plan selectedAgentId is not registered"));
        if (descriptor.agentCard() != null
                && descriptor.agentCard().supportedDomains() != null
                && !descriptor.agentCard().supportedDomains().isEmpty()
                && !descriptor.agentCard().supportedDomains().contains(plan.domain())) {
            throw new IllegalArgumentException("workflow plan selectedAgentId does not support the plan domain");
        }
    }
}
