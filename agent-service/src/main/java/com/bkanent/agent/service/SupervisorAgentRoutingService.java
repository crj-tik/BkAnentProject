package com.bkanent.agent.service;

import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class SupervisorAgentRoutingService {

    private final AgentRegistry agentRegistry;
    private final SupervisorGovernanceService supervisorGovernanceService;

    public SupervisorAgentRoutingService(AgentRegistry agentRegistry,
                                         SupervisorGovernanceService supervisorGovernanceService) {
        this.agentRegistry = agentRegistry;
        this.supervisorGovernanceService = supervisorGovernanceService;
    }

    public RegisteredAgentDescriptor selectAgent(String domain,
                                                 String message,
                                                 Map<String, Object> context) {
        String preferredAgentId = supervisorGovernanceService.resolvePreferredAgentOverride(domain, context);
        if (StringUtils.hasText(preferredAgentId)) {
            return agentRegistry.getByAgentId(preferredAgentId)
                    .orElseThrow(() -> new IllegalStateException("preferred agent not found: " + preferredAgentId));
        }
        return selectDefaultAgent(domain, message);
    }

    private RegisteredAgentDescriptor selectDefaultAgent(String domain, String message) {
        List<RegisteredAgentDescriptor> matchedAgents = agentRegistry.findByDomain(domain);
        if (!matchedAgents.isEmpty()) {
            return matchedAgents.get(0);
        }
        List<RegisteredAgentDescriptor> listingAgents = agentRegistry.findByDomain("listing");
        if (!listingAgents.isEmpty() && containsListingIntent(message)) {
            return listingAgents.get(0);
        }
        return agentRegistry.getByAgentId("listing-agent")
                .or(() -> listingAgents.stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No registered agent available"));
    }

    private boolean containsListingIntent(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return message.contains("房源")
                || message.contains("找房")
                || message.contains("小区")
                || message.contains("listing")
                || message.contains("房子");
    }
}
