package com.bkanent.agent.graph.node;

import com.bkanent.agent.catalog.DomainCatalog;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.graph.SupervisorGraphNode;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.model.distributed.WorkflowPlan;
import com.bkanent.agent.service.SupervisorIntentPlanningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class PlanTaskNode implements SupervisorGraphNode {

    private static final Logger log = LoggerFactory.getLogger(PlanTaskNode.class);

    private final SupervisorIntentPlanningService supervisorIntentPlanningService;
    private final DomainCatalog domainCatalog;
    private final DistributedAgentProperties distributedAgentProperties;

    public PlanTaskNode(SupervisorIntentPlanningService supervisorIntentPlanningService,
                        DomainCatalog domainCatalog,
                        DistributedAgentProperties distributedAgentProperties) {
        this.supervisorIntentPlanningService = supervisorIntentPlanningService;
        this.domainCatalog = domainCatalog;
        this.distributedAgentProperties = distributedAgentProperties;
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
                    .filter(StringUtils::hasText)
                    .toList();
            if (domains.size() > 1) {
                return domains;
            }
        }
        if (context == null || !Boolean.TRUE.equals(context.get("requireParallel"))) {
            return List.of();
        }
        for (DistributedAgentProperties.ParallelRoutingRule rule
                : distributedAgentProperties.getCatalog().getRuleRouting().getParallelRules()) {
            List<String> domains = rule.getDomains() == null ? List.of() : rule.getDomains();
            if (domains.size() < 2 || !matchesAllKeywordGroups(message, rule)) {
                continue;
            }
            if (!domains.stream().allMatch(domainCatalog::contains)) {
                log.warn("Parallel routing rule targets non-catalog domains {}; rule skipped", domains);
                continue;
            }
            return domains;
        }
        return List.of();
    }

    private boolean matchesAllKeywordGroups(String message,
                                            DistributedAgentProperties.ParallelRoutingRule rule) {
        if (rule.getKeywordGroups() == null || rule.getKeywordGroups().isEmpty()) {
            return false;
        }
        return rule.getKeywordGroups().stream()
                .allMatch(group -> group != null && group.stream()
                        .anyMatch(keyword -> StringUtils.hasText(keyword) && message.contains(keyword)));
    }
}
