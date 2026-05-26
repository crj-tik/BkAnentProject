package com.bkanent.agent.registry;

import com.bkanent.common.agent.AgentCard;

import java.util.List;
import java.util.Optional;

/**
 * AgentRegistry Agent 注册表。
 */
public interface AgentRegistry {

    Optional<RegisteredAgentDescriptor> getByAgentId(String agentId);

    List<RegisteredAgentDescriptor> findByDomain(String domain);

    List<AgentCard> listCards();

    List<RegisteredAgentDescriptor> listDescriptors();
}
