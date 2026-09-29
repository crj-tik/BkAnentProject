package com.bkanent.agent.service;

import com.bkanent.agent.catalog.DomainCatalog;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.model.distributed.WorkflowPlan;
import com.bkanent.agent.model.distributed.WorkflowPlanStep;
import com.bkanent.common.agent.AgentCard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SupervisorIntentPlanningService {

    private static final String PLAN_CONTEXT_KEY = "llmWorkflowPlan";
    private static final String PLAN_SELECTED_AGENT_KEY = "llmSelectedAgentId";

    private final DistributedAgentProperties distributedAgentProperties;
    private final AgentChatService agentChatService;
    private final WorkflowPlanValidator workflowPlanValidator;
    private final DomainCatalog domainCatalog;
    private final ObjectMapper objectMapper;

    public SupervisorIntentPlanningService(DistributedAgentProperties distributedAgentProperties,
                                           AgentChatService agentChatService,
                                           WorkflowPlanValidator workflowPlanValidator,
                                           DomainCatalog domainCatalog,
                                           ObjectMapper objectMapper) {
        this.distributedAgentProperties = distributedAgentProperties;
        this.agentChatService = agentChatService;
        this.workflowPlanValidator = workflowPlanValidator;
        this.domainCatalog = domainCatalog;
        this.objectMapper = objectMapper;
    }

    public WorkflowPlan tryPlan(String userMessage, Map<String, Object> context) {
        if (!distributedAgentProperties.getPlanning().isLlmEnabled()) {
            return null;
        }
        String strategy = distributedAgentProperties.getPlanning().getStrategy();
        if (!StringUtils.hasText(strategy) || "rule-first".equalsIgnoreCase(strategy)) {
            return null;
        }
        DomainCatalog.CatalogSnapshot snapshot = domainCatalog.snapshot();
        String raw = agentChatService.call(systemPrompt(snapshot), userPrompt(userMessage, context), false);
        WorkflowPlan plan = parse(raw);
        return workflowPlanValidator.validate(plan);
    }

    public Map<String, Object> enrichContext(Map<String, Object> context, WorkflowPlan plan) {
        if (plan == null) {
            return context == null ? Map.of() : context;
        }
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>();
        if (context != null) {
            merged.putAll(context);
        }
        merged.put(PLAN_CONTEXT_KEY, plan);
        if (StringUtils.hasText(plan.selectedAgentId())) {
            merged.put(PLAN_SELECTED_AGENT_KEY, plan.selectedAgentId());
        }
        return Map.copyOf(merged);
    }

    public WorkflowPlan readPlan(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object value = context.get(PLAN_CONTEXT_KEY);
        if (value instanceof WorkflowPlan plan) {
            return plan;
        }
        return null;
    }

    public String readSelectedAgentId(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object value = context.get(PLAN_SELECTED_AGENT_KEY);
        return value == null ? null : String.valueOf(value);
    }

    private WorkflowPlan parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            List<String> parallelDomains = new ArrayList<>();
            JsonNode parallelNode = root.path("parallelDomains");
            if (parallelNode.isArray()) {
                for (JsonNode node : parallelNode) {
                    if (!node.isNull() && StringUtils.hasText(node.asText())) {
                        parallelDomains.add(node.asText());
                    }
                }
            }
            List<WorkflowPlanStep> steps = new ArrayList<>();
            JsonNode stepsNode = root.path("steps");
            if (stepsNode.isArray()) {
                for (JsonNode node : stepsNode) {
                    steps.add(new WorkflowPlanStep(
                            text(node, "type"),
                            text(node, "domain"),
                            text(node, "intent"),
                            text(node, "approvalType"),
                            Map.of()
                    ));
                }
            }
            return new WorkflowPlan(
                    text(root, "intent"),
                    text(root, "domain"),
                    text(root, "workflowType"),
                    root.path("requireApproval").isMissingNode() ? null : root.path("requireApproval").asBoolean(),
                    List.copyOf(parallelDomains),
                    text(root, "selectedAgentId"),
                    List.copyOf(steps),
                    agentChatService.getModel(),
                    raw
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse workflow plan", exception);
        }
    }

    private String systemPrompt(DomainCatalog.CatalogSnapshot snapshot) {
        return """
                You are a supervisor planning model for a distributed multi-agent system.
                Return only JSON.
                Decide:
                - domain
                - intent
                - workflowType
                - requireApproval
                - parallelDomains
                - selectedAgentId
                - steps
                Allowed domains (dynamically derived from the registered agent catalog):
                %s
                For intent, prefer a skill id declared by the selected domain's agent (listed above);
                otherwise use a concise domain-scoped intent identifier.
                Allowed workflowType: single_agent, parallel, marketing_pipeline,
                or "<domain>_with_approval" where <domain> is one of the allowed domains above.
                """.formatted(domainCatalogLines(snapshot));
    }

    private String domainCatalogLines(DomainCatalog.CatalogSnapshot snapshot) {
        StringBuilder lines = new StringBuilder();
        for (String domain : snapshot.domains()) {
            lines.append("- ").append(domain);
            AgentCard representative = null;
            for (AgentCard card : snapshot.cards()) {
                if (card.supportedDomains() != null && card.supportedDomains().contains(domain)) {
                    representative = card;
                    break;
                }
            }
            if (representative != null) {
                if (StringUtils.hasText(representative.description())) {
                    lines.append(": ").append(representative.description());
                }
                if (representative.supportedSkills() != null && !representative.supportedSkills().isEmpty()) {
                    lines.append(" (skills: ").append(String.join(", ", representative.supportedSkills())).append(")");
                }
            }
            lines.append('\n');
        }
        return lines.toString();
    }

    private String userPrompt(String userMessage, Map<String, Object> context) {
        return """
                Generate a workflow execution plan for this request.
                Respond with JSON only.

                userMessage:
                %s

                context:
                %s
                """.formatted(userMessage == null ? "" : userMessage, context == null ? "{}" : String.valueOf(context));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
