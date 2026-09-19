package com.bkanent.agent.registry;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.common.agent.AgentCard;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * StaticAgentRegistry 静态 Agent 注册表实现。
 */
@Component
public class StaticAgentRegistry implements AgentRegistry {

    private final Map<String, RegisteredAgentDescriptor> descriptors;

    public StaticAgentRegistry(DistributedAgentProperties properties) {
        List<String> errors = new ArrayList<>();
        properties.getAgents().values().stream()
                .filter(registration -> StringUtils.hasText(registration.getAgentId()))
                .forEach(registration -> errors.addAll(
                        OfficialA2aRegistrationValidator.validate(registration, properties.getAgentCardPath())));
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        this.descriptors = properties.getAgents().values().stream()
                .filter(registration -> StringUtils.hasText(registration.getAgentId()))
                .map(registration -> buildDescriptor(registration, properties.getAgentCardPath()))
                .collect(Collectors.toUnmodifiableMap(
                        RegisteredAgentDescriptor::agentId,
                        Function.identity()
                ));
    }

    private RegisteredAgentDescriptor buildDescriptor(DistributedAgentProperties.AgentRegistration registration,
                                                       String defaultCardPath) {
        String cardPath = resolveCardPath(registration, defaultCardPath);
        String a2aPath = resolveA2aPath(registration);
        String baseUrl = registration.getBaseUrl();
        AgentCard card = new AgentCard(
                registration.getAgentId(),
                registration.getName(),
                registration.getDescription(),
                registration.getVersion(),
                List.copyOf(registration.getSupportedSkills()),
                List.copyOf(registration.getSupportedDomains()),
                registration.isSupportsStreaming(),
                registration.isSupportsAsyncTask(),
                joinUrl(baseUrl, a2aPath),
                List.copyOf(registration.getInputModes()),
                List.copyOf(registration.getOutputModes())
        );
        RegisteredAgentDescriptor descriptor = new RegisteredAgentDescriptor(
                registration.getAgentId(),
                baseUrl,
                cardPath,
                a2aPath,
                AgentRuntimeType.ALIBABA_A2A,
                AgentDescriptorSource.STATIC_CONFIG,
                card
        );
        OfficialA2aRegistrationValidator.requireValidDescriptor(descriptor);
        return descriptor;
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

    private String resolveCardPath(DistributedAgentProperties.AgentRegistration registration,
                                   String defaultCardPath) {
        return StringUtils.hasText(registration.getAgentCardPath())
                ? registration.getAgentCardPath()
                : defaultCardPath;
    }

    private String resolveA2aPath(DistributedAgentProperties.AgentRegistration registration) {
        return StringUtils.hasText(registration.getA2aPath()) ? registration.getA2aPath() : "/a2a";
    }

    private String joinUrl(String baseUrl, String path) {
        if (!StringUtils.hasText(baseUrl)) {
            return path;
        }
        return baseUrl.endsWith("/") && path.startsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + path
                : baseUrl + path;
    }
}
