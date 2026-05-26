package com.bkanent.agent.service;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.model.distributed.WorkflowPlan;
import com.bkanent.agent.model.distributed.WorkflowPlanStep;
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
    private final ObjectMapper objectMapper;

    public SupervisorIntentPlanningService(DistributedAgentProperties distributedAgentProperties,
                                           AgentChatService agentChatService,
                                           WorkflowPlanValidator workflowPlanValidator,
                                           ObjectMapper objectMapper) {
        this.distributedAgentProperties = distributedAgentProperties;
        this.agentChatService = agentChatService;
        this.workflowPlanValidator = workflowPlanValidator;
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
        String raw = agentChatService.call(systemPrompt(), userPrompt(userMessage, context), false);
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

    private String systemPrompt() {
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
                Allowed domains: listing, marketing, media, trade, contract, settlement, notification.
                Allowed workflowType: single_agent, parallel, marketing_pipeline, marketing_with_approval, trade_with_approval, contract_with_approval, listing_with_approval.
                """;
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
