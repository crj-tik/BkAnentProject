package com.bkanent.agent.registry;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.common.agent.AgentCard;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * StaticAgentRegistry 静态 Agent 注册表实现。
 */
@Component
public class StaticAgentRegistry implements AgentRegistry {

    private final Map<String, RegisteredAgentDescriptor> descriptors;

    public StaticAgentRegistry(DistributedAgentProperties properties) {
        this.descriptors = properties.getAgents().values().stream()
                .filter(registration -> StringUtils.hasText(registration.getAgentId()))
                .collect(Collectors.toUnmodifiableMap(
                        DistributedAgentProperties.AgentRegistration::getAgentId,
                        registration -> new RegisteredAgentDescriptor(
                                registration.getAgentId(),
                                registration.getBaseUrl(),
                                registration.getAgentCardPath(),
                                registration.getA2aPath(),
                                registration.getA2aTaskCreatePath(),
                                registration.getA2aTaskStatusPath(),
                                registration.getA2aTaskStreamPath(),
                                resolveRuntimeType(registration),
                                resolveOfficialPayloadMode(registration),
                                AgentDescriptorSource.STATIC_CONFIG,
                                new AgentCard(
                                        registration.getAgentId(),
                                        registration.getName(),
                                        registration.getDescription(),
                                        registration.getVersion(),
                                        List.copyOf(registration.getSupportedSkills()),
                                        List.copyOf(registration.getSupportedDomains()),
                                        registration.isSupportsStreaming(),
                                        registration.isSupportsAsyncTask(),
                                        registration.getBaseUrl() + registration.getA2aPath(),
                                        List.copyOf(registration.getInputModes()),
                                        List.copyOf(registration.getOutputModes())
                                )
                        )
                ));
    }

    private AgentRuntimeType resolveRuntimeType(DistributedAgentProperties.AgentRegistration registration) {
        if ("official".equalsIgnoreCase(registration.getRuntimeProvider())) {
            return AgentRuntimeType.ALIBABA_A2A;
        }
        if ("custom".equalsIgnoreCase(registration.getRuntimeProvider())) {
            return AgentRuntimeType.CUSTOM_HTTP;
        }
        if (registration.getAgentCardPath() != null && registration.getAgentCardPath().contains(".well-known/agent.json")) {
            return AgentRuntimeType.ALIBABA_A2A;
        }
        return AgentRuntimeType.CUSTOM_HTTP;
    }

    private String resolveOfficialPayloadMode(DistributedAgentProperties.AgentRegistration registration) {
        if (registration == null || !StringUtils.hasText(registration.getOfficialPayloadMode())) {
            return "auto";
        }
        return registration.getOfficialPayloadMode().trim().toLowerCase();
    }

    @Override
    public Optional<RegisteredAgentDescriptor> getByAgentId(String agentId) {
        return Optional.ofNullable(descriptors.get(agentId));
    }

    @Override
    public List<RegisteredAgentDescriptor> findByDomain(String domain) {
        if (!StringUtils.hasText(domain)) {
            return List.copyOf(descriptors.values());
        }
        return descriptors.values().stream()
                .filter(descriptor -> descriptor.agentCard().supportedDomains() != null
                        && descriptor.agentCard().supportedDomains().contains(domain))
                .toList();
    }

    @Override
    public List<AgentCard> listCards() {
        return descriptors.values().stream()
                .map(RegisteredAgentDescriptor::agentCard)
                .toList();
    }

    @Override
    public List<RegisteredAgentDescriptor> listDescriptors() {
        return List.copyOf(descriptors.values());
    }
}
