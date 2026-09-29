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

import java.util.Map;

@Component
public class ParseIntentNode implements SupervisorGraphNode {

    private static final Logger log = LoggerFactory.getLogger(ParseIntentNode.class);

    private final SupervisorIntentPlanningService supervisorIntentPlanningService;
    private final DomainCatalog domainCatalog;
    private final DistributedAgentProperties distributedAgentProperties;

    public ParseIntentNode(SupervisorIntentPlanningService supervisorIntentPlanningService,
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
            return state.withIntent(
                    workflowPlan.intent(),
                    workflowPlan.domain(),
                    workflowPlan.workflowType()
            );
        }
        String domain = resolveDomain(context, message);
        String intent = domainCatalog.resolveDefaultIntent(domain);
        if (!StringUtils.hasText(intent)) {
            throw new IllegalStateException("No default intent resolvable for domain " + domain);
        }
        String workflowType = resolveWorkflowType(domain, message, context);
        return state.withIntent(intent, domain, workflowType);
    }

    private String resolveDomain(Map<String, Object> context, String message) {
        Object contextDomain = context == null ? null : context.get("domain");
        if (contextDomain instanceof String explicit && StringUtils.hasText(explicit)) {
            return explicit;
        }
        for (Map.Entry<String, java.util.List<String>> entry
                : distributedAgentProperties.getCatalog().getRuleRouting().getKeywords().entrySet()) {
            String domain = entry.getKey();
            if (!domainCatalog.contains(domain)) {
                continue;
            }
            for (String keyword : entry.getValue()) {
                if (StringUtils.hasText(keyword) && message.contains(keyword)) {
                    return domain;
                }
            }
        }
        return distributedAgentProperties.getCatalog().getRuleRouting().getDefaultDomain();
    }

    private String resolveWorkflowType(String domain, String message, Map<String, Object> context) {
        if (context != null && context.get("parallelDomains") instanceof java.util.Collection<?> collection && collection.size() > 1) {
            return "parallel";
        }
        if (Boolean.TRUE.equals(context == null ? null : context.get("requireApproval"))) {
            return domain + "_with_approval";
        }
        if (matchesKeyword(message, "marketing")) {
            return "marketing_pipeline";
        }
        return "single_agent";
    }

    private boolean matchesKeyword(String message, String domain) {
        java.util.List<String> keywords = distributedAgentProperties.getCatalog()
                .getRuleRouting().getKeywords().get(domain);
        if (keywords == null) {
            return false;
        }
        return keywords.stream().anyMatch(keyword -> StringUtils.hasText(keyword) && message.contains(keyword));
    }
}
