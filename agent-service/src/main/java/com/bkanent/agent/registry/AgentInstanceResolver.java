package com.bkanent.agent.registry;

import com.bkanent.agent.config.DistributedAgentProperties;

import java.util.Optional;

public interface AgentInstanceResolver {

    Optional<String> resolveBaseUrl(DistributedAgentProperties.AgentRegistration registration);
}
