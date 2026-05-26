package com.bkanent.agent.graph.node;

import com.bkanent.agent.graph.SupervisorGraphNode;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.model.distributed.WorkflowPlan;
import com.bkanent.agent.service.SupervisorIntentPlanningService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class PlanTaskNode implements SupervisorGraphNode {

    private final SupervisorIntentPlanningService supervisorIntentPlanningService;

    public PlanTaskNode(SupervisorIntentPlanningService supervisorIntentPlanningService) {
        this.supervisorIntentPlanningService = supervisorIntentPlanningService;
    }

    @Override
    public SupervisorGraphState apply(SupervisorGraphState state) {
        Map<String, Object> context = state.sharedContext();
        String message = state.userMessage() == null ? "" : state.userMessage();
        WorkflowPlan workflowPlan = supervisorIntentPlanningService.readPlan(context);
        if (workflowPlan != null) {
            List<String> parallelDomains = workflowPlan.parallelDomains() == null ? List.of() : workflowPlan.parallelDomains();
            boolean requireParallel = parallelDomains.size() > 1;
            boolean requireApproval = Boolean.TRUE.equals(workflowPlan.requireApproval());
            return state.withPlan(requireParallel, requireApproval, parallelDomains);
        }
        List<String> parallelDomains = resolveParallelDomains(context, message);
        boolean requireParallel = parallelDomains.size() > 1;
        boolean requireApproval = context != null && Boolean.TRUE.equals(context.get("requireApproval"));
        return state.withPlan(requireParallel, requireApproval, parallelDomains);
    }

    private List<String> resolveParallelDomains(Map<String, Object> context, String message) {
        if (context != null && context.get("parallelDomains") instanceof Collection<?> collection) {
            List<String> domains = collection.stream()
                    .map(String::valueOf)
                    .filter(org.springframework.util.StringUtils::hasText)
                    .toList();
            if (domains.size() > 1) {
                return domains;
            }
        }
        if (context != null && Boolean.TRUE.equals(context.get("requireParallel"))
                && containsListingIntent(message) && containsTradeIntent(message)) {
            return List.of("listing", "trade");
        }
        return List.of();
    }

    private boolean containsListingIntent(String message) {
        return message.contains("房源")
                || message.contains("找房")
                || message.contains("小区")
                || message.contains("listing")
                || message.contains("房子");
    }

    private boolean containsTradeIntent(String message) {
        return message.contains("交易")
                || message.contains("成交")
                || message.contains("风险")
                || message.contains("可行性")
                || message.contains("trade");
    }
}
