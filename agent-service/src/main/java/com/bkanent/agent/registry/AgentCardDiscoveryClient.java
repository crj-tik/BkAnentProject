package com.bkanent.agent.registry;

import com.bkanent.common.agent.AgentCard;

import java.util.Optional;

public interface AgentCardDiscoveryClient {

    Optional<AgentCard> fetchAgentCard(String baseUrl, String cardPath);

    default Optional<AgentCard> fetchByAgentName(String agentName) {
        return Optional.empty();
    }
}
