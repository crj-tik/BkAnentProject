package com.bkanent.agent.registry;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.common.agent.AgentCard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Primary
@Component
public class DynamicAgentRegistry implements AgentRegistry {

    private final DistributedAgentProperties properties;
    private final AgentCardDiscoveryClient agentCardDiscoveryClient;
    private final AgentInstanceResolver agentInstanceResolver;
    private final ObjectProvider<DiscoveryClient> discoveryClientProvider;
    private final Map<String, RegisteredAgentDescriptor> descriptors = new ConcurrentHashMap<>();
    private final Map<String, Long> refreshedAt = new ConcurrentHashMap<>();

    public DynamicAgentRegistry(DistributedAgentProperties properties,
                                AgentCardDiscoveryClient agentCardDiscoveryClient,
                                AgentInstanceResolver agentInstanceResolver,
                                ObjectProvider<DiscoveryClient> discoveryClientProvider) {
        this.properties = properties;
        this.agentCardDiscoveryClient = agentCardDiscoveryClient;
        this.agentInstanceResolver = agentInstanceResolver;
        this.discoveryClientProvider = discoveryClientProvider;
        if (!properties.getCatalog().isStrictNacos()) {
            properties.getAgents().forEach((key, registration) -> {
                if (StringUtils.hasText(registration.getAgentId())) {
                    descriptors.put(registration.getAgentId(), buildStaticDescriptor(registration, resolveBaseUrl(registration)));
                }
            });
        }
    }

    @Override
    public Optional<RegisteredAgentDescriptor> getByAgentId(String agentId) {
        refreshAll();
        refreshDescriptor(agentId);
        return Optional.ofNullable(descriptors.get(agentId));
    }

    @Override
    public List<RegisteredAgentDescriptor> findByDomain(String domain) {
        refreshAll();
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
        refreshAll();
        return descriptors.values().stream()
                .map(RegisteredAgentDescriptor::agentCard)
                .toList();
    }

    @Override
    public List<RegisteredAgentDescriptor> listDescriptors() {
        refreshAll();
        return List.copyOf(descriptors.values());
    }

    private void refreshAll() {
        if (!properties.isDiscoveryEnabled()) {
            return;
        }
        if (refreshAllFromDiscovery()) {
            return;
        }
        if (properties.getCatalog().isStrictNacos()) {
            return;
        }
        properties.getAgents().values().forEach(registration -> refreshDescriptor(registration.getAgentId()));
    }

    private void refreshDescriptor(String agentId) {
        if (!properties.isDiscoveryEnabled() || !StringUtils.hasText(agentId)) {
            return;
        }
        if (!shouldRefresh(agentId)) {
            return;
        }
        DistributedAgentProperties.AgentRegistration registration = findRegistration(agentId);
        if (registration == null && properties.getCatalog().isStrictNacos()) {
            return;
        }
        String resolvedBaseUrl = resolveBaseUrl(registration);
        if (registration == null || !StringUtils.hasText(resolvedBaseUrl)) {
            return;
        }
        agentCardDiscoveryClient.fetchByAgentName(agentId)
                .or(() -> agentCardDiscoveryClient.fetchAgentCard(resolvedBaseUrl, resolveCardPath(registration)))
                .map(card -> buildDiscoveredDescriptor(registration, resolvedBaseUrl, card))
                .ifPresentOrElse(descriptor -> descriptors.put(agentId, descriptor),
                        () -> descriptors.put(agentId, buildStaticDescriptor(registration, resolvedBaseUrl)));
        refreshedAt.put(agentId, System.currentTimeMillis());
    }

    private boolean refreshAllFromDiscovery() {
        DiscoveryClient discoveryClient = discoveryClientProvider.getIfAvailable();
        if (discoveryClient == null) {
            return false;
        }
        if (properties.getCatalog().isStrictNacos()) {
            descriptors.clear();
        }
        List<String> serviceIds = discoveryClient.getServices();
        if (serviceIds == null || serviceIds.isEmpty()) {
            return false;
        }
        boolean discoveredAny = false;
        for (String serviceId : serviceIds) {
            if (!StringUtils.hasText(serviceId)) {
                continue;
            }
            List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
            if (instances == null || instances.isEmpty()) {
                continue;
            }
            ServiceInstance instance = instances.get(0);
            Map<String, String> metadata = instance.getMetadata() == null ? Map.of() : instance.getMetadata();
            DistributedAgentProperties.AgentRegistration registration = findRegistrationByServiceId(serviceId);
            String agentId = resolveAgentId(serviceId, metadata, registration);
            if (!StringUtils.hasText(agentId)) {
                continue;
            }
            if (!shouldRefresh(agentId)) {
                discoveredAny = true;
                continue;
            }
            String baseUrl = resolveInstanceBaseUrl(instance);
            String cardPath = resolveCardPath(metadata, registration);
            RegisteredAgentDescriptor descriptor = agentCardDiscoveryClient.fetchByAgentName(agentId)
                    .or(() -> agentCardDiscoveryClient.fetchAgentCard(baseUrl, cardPath))
                    .map(card -> buildDiscoveredDescriptor(serviceId, registration, metadata, baseUrl, cardPath, card))
                    .orElseGet(() -> buildMetadataDescriptor(serviceId, registration, metadata, baseUrl, cardPath));
            descriptors.put(agentId, descriptor);
            refreshedAt.put(agentId, System.currentTimeMillis());
            discoveredAny = true;
        }
        return discoveredAny;
    }

    private DistributedAgentProperties.AgentRegistration findRegistration(String agentId) {
        return properties.getAgents().values().stream()
                .filter(registration -> agentId.equals(registration.getAgentId()))
                .findFirst()
                .orElse(null);
    }

    private DistributedAgentProperties.AgentRegistration findRegistrationByServiceId(String serviceId) {
        return properties.getAgents().values().stream()
                .filter(registration -> serviceId.equals(registration.getServiceId()))
                .findFirst()
                .orElse(null);
    }

    private RegisteredAgentDescriptor buildStaticDescriptor(DistributedAgentProperties.AgentRegistration registration,
                                                            String baseUrl) {
        return new RegisteredAgentDescriptor(
                registration.getAgentId(),
                baseUrl,
                resolveCardPath(registration),
                registration.getA2aPath(),
                registration.getA2aTaskCreatePath(),
                registration.getA2aTaskStatusPath(),
                registration.getA2aTaskStreamPath(),
                resolveRuntimeType(Map.of(), registration, resolveCardPath(registration), baseUrl + registration.getA2aPath()),
                resolveOfficialPayloadMode(Map.of(), registration),
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
                        baseUrl + registration.getA2aPath(),
                        List.copyOf(registration.getInputModes()),
                        List.copyOf(registration.getOutputModes())
                )
        );
    }

    private RegisteredAgentDescriptor buildDiscoveredDescriptor(DistributedAgentProperties.AgentRegistration registration,
                                                                String baseUrl,
                                                                AgentCard agentCard) {
        return buildDiscoveredDescriptor(null, registration, Map.of(), baseUrl, resolveCardPath(registration), agentCard);
    }

    private RegisteredAgentDescriptor buildDiscoveredDescriptor(String serviceId,
                                                                DistributedAgentProperties.AgentRegistration registration,
                                                                Map<String, String> metadata,
                                                                String baseUrl,
                                                                String cardPath,
                                                                AgentCard agentCard) {
        String endpoint = agentCard.a2aEndpoint();
        String resolvedPath = registration == null ? "/a2a" : registration.getA2aPath();
        if (StringUtils.hasText(endpoint) && endpoint.startsWith(baseUrl)) {
            resolvedPath = endpoint.substring(baseUrl.length());
        }
        String agentId = resolveAgentId(serviceId, metadata, registration);
        return new RegisteredAgentDescriptor(
                agentId,
                baseUrl,
                cardPath,
                resolvedPath,
                resolveTaskPath(metadata, registration, "a2a-task-create-path", registration == null ? "/a2a" : registration.getA2aTaskCreatePath()),
                resolveTaskPath(metadata, registration, "a2a-task-status-path", registration == null ? "/a2a" : registration.getA2aTaskStatusPath()),
                resolveTaskPath(metadata, registration, "a2a-task-stream-path", registration == null ? "/a2a" : registration.getA2aTaskStreamPath()),
                resolveRuntimeType(metadata, registration, cardPath, agentCard.a2aEndpoint()),
                resolveOfficialPayloadMode(metadata, registration),
                AgentDescriptorSource.DISCOVERED_CARD,
                new AgentCard(
                        StringUtils.hasText(agentCard.agentId()) ? agentCard.agentId() : agentId,
                        StringUtils.hasText(agentCard.name()) ? agentCard.name() : resolveName(serviceId, metadata, registration),
                        StringUtils.hasText(agentCard.description()) ? agentCard.description() : resolveDescription(serviceId, metadata, registration),
                        StringUtils.hasText(agentCard.version()) ? agentCard.version() : resolveVersion(registration),
                        agentCard.supportedSkills() == null || agentCard.supportedSkills().isEmpty()
                                ? resolveSkills(metadata, registration)
                                : agentCard.supportedSkills(),
                        agentCard.supportedDomains() == null || agentCard.supportedDomains().isEmpty()
                                ? resolveDomains(metadata, registration)
                                : agentCard.supportedDomains(),
                        resolveBoolean(agentCard.supportsStreaming(), resolveSupportsStreaming(metadata, registration)),
                        resolveBoolean(agentCard.supportsAsyncTask(), resolveSupportsAsyncTask(metadata, registration)),
                        StringUtils.hasText(agentCard.a2aEndpoint()) ? agentCard.a2aEndpoint() : baseUrl + resolvedPath,
                        agentCard.inputModes() == null || agentCard.inputModes().isEmpty()
                                ? resolveInputModes(metadata, registration)
                                : agentCard.inputModes(),
                        agentCard.outputModes() == null || agentCard.outputModes().isEmpty()
                                ? resolveOutputModes(metadata, registration)
                                : agentCard.outputModes()
                )
        );
    }

    private RegisteredAgentDescriptor buildMetadataDescriptor(String serviceId,
                                                              DistributedAgentProperties.AgentRegistration registration,
                                                              Map<String, String> metadata,
                                                              String baseUrl,
                                                              String cardPath) {
        String agentId = resolveAgentId(serviceId, metadata, registration);
        String a2aPath = resolveTaskPath(metadata, registration, "a2a-path", registration == null ? "/a2a" : registration.getA2aPath());
        return new RegisteredAgentDescriptor(
                agentId,
                baseUrl,
                cardPath,
                a2aPath,
                resolveTaskPath(metadata, registration, "a2a-task-create-path", registration == null ? "/a2a" : registration.getA2aTaskCreatePath()),
                resolveTaskPath(metadata, registration, "a2a-task-status-path", registration == null ? "/a2a" : registration.getA2aTaskStatusPath()),
                resolveTaskPath(metadata, registration, "a2a-task-stream-path", registration == null ? "/a2a" : registration.getA2aTaskStreamPath()),
                resolveRuntimeType(metadata, registration, cardPath, baseUrl + a2aPath),
                resolveOfficialPayloadMode(metadata, registration),
                AgentDescriptorSource.DISCOVERED_CARD,
                new AgentCard(
                        agentId,
                        resolveName(serviceId, metadata, registration),
                        resolveDescription(serviceId, metadata, registration),
                        resolveVersion(registration),
                        resolveSkills(metadata, registration),
                        resolveDomains(metadata, registration),
                        resolveSupportsStreaming(metadata, registration),
                        resolveSupportsAsyncTask(metadata, registration),
                        baseUrl + a2aPath,
                        resolveInputModes(metadata, registration),
                        resolveOutputModes(metadata, registration)
                )
        );
    }

    private boolean resolveBoolean(Boolean discoveredValue, boolean fallbackValue) {
        return discoveredValue != null ? discoveredValue : fallbackValue;
    }

    private AgentRuntimeType resolveRuntimeType(Map<String, String> metadata,
                                                DistributedAgentProperties.AgentRegistration registration,
                                                String cardPath,
                                                String endpoint) {
        String provider = metadata == null ? null : metadata.get("agent-runtime-provider");
        if ("official".equalsIgnoreCase(provider)) {
            return AgentRuntimeType.ALIBABA_A2A;
        }
        if ("custom".equalsIgnoreCase(provider)) {
            return AgentRuntimeType.CUSTOM_HTTP;
        }
        if (registration != null) {
            if ("official".equalsIgnoreCase(registration.getRuntimeProvider())) {
                return AgentRuntimeType.ALIBABA_A2A;
            }
            if ("custom".equalsIgnoreCase(registration.getRuntimeProvider())) {
                return AgentRuntimeType.CUSTOM_HTTP;
            }
        }
        if (StringUtils.hasText(cardPath) && cardPath.contains(".well-known/agent.json")) {
            return AgentRuntimeType.ALIBABA_A2A;
        }
        if (StringUtils.hasText(endpoint) && endpoint.contains("/a2a")) {
            return AgentRuntimeType.ALIBABA_A2A;
        }
        return AgentRuntimeType.CUSTOM_HTTP;
    }

    private String resolveOfficialPayloadMode(Map<String, String> metadata,
                                              DistributedAgentProperties.AgentRegistration registration) {
        String metadataValue = metadata == null ? null : metadata.get("agent-official-payload-mode");
        if (StringUtils.hasText(metadataValue)) {
            return metadataValue.trim().toLowerCase();
        }
        if (registration == null || !StringUtils.hasText(registration.getOfficialPayloadMode())) {
            return "auto";
        }
        return registration.getOfficialPayloadMode().trim().toLowerCase();
    }

    private String resolveAgentId(String serviceId,
                                  Map<String, String> metadata,
                                  DistributedAgentProperties.AgentRegistration registration) {
        String metadataValue = metadata == null ? null : metadata.get("agent-id");
        if (StringUtils.hasText(metadataValue)) {
            return metadataValue.trim();
        }
        if (registration != null && StringUtils.hasText(registration.getAgentId())) {
            return registration.getAgentId();
        }
        if (!StringUtils.hasText(serviceId)) {
            return null;
        }
        if (serviceId.endsWith("-service")) {
            return serviceId.substring(0, serviceId.length() - "-service".length()) + "-agent";
        }
        return serviceId + "-agent";
    }

    private String resolveName(String serviceId,
                               Map<String, String> metadata,
                               DistributedAgentProperties.AgentRegistration registration) {
        if (registration != null && StringUtils.hasText(registration.getName())) {
            return registration.getName();
        }
        String agentId = resolveAgentId(serviceId, metadata, registration);
        return StringUtils.hasText(agentId) ? agentId : serviceId;
    }

    private String resolveDescription(String serviceId,
                                      Map<String, String> metadata,
                                      DistributedAgentProperties.AgentRegistration registration) {
        if (registration != null && StringUtils.hasText(registration.getDescription())) {
            return registration.getDescription();
        }
        return "Discovered agent from Nacos: " + resolveName(serviceId, metadata, registration);
    }

    private String resolveVersion(DistributedAgentProperties.AgentRegistration registration) {
        if (registration != null && StringUtils.hasText(registration.getVersion())) {
            return registration.getVersion();
        }
        return "1.0.0";
    }

    private List<String> resolveDomains(Map<String, String> metadata,
                                        DistributedAgentProperties.AgentRegistration registration) {
        List<String> values = parseMetadataList(metadata, "agent-domains");
        if (!values.isEmpty()) {
            return values;
        }
        if (registration != null && registration.getSupportedDomains() != null && !registration.getSupportedDomains().isEmpty()) {
            return List.copyOf(registration.getSupportedDomains());
        }
        return List.of();
    }

    private List<String> resolveSkills(Map<String, String> metadata,
                                       DistributedAgentProperties.AgentRegistration registration) {
        if (registration != null && registration.getSupportedSkills() != null && !registration.getSupportedSkills().isEmpty()) {
            return List.copyOf(registration.getSupportedSkills());
        }
        return List.of();
    }

    private boolean resolveSupportsStreaming(Map<String, String> metadata,
                                             DistributedAgentProperties.AgentRegistration registration) {
        return registration != null && registration.isSupportsStreaming();
    }

    private boolean resolveSupportsAsyncTask(Map<String, String> metadata,
                                             DistributedAgentProperties.AgentRegistration registration) {
        return registration != null && registration.isSupportsAsyncTask();
    }

    private List<String> resolveInputModes(Map<String, String> metadata,
                                           DistributedAgentProperties.AgentRegistration registration) {
        if (registration != null && registration.getInputModes() != null && !registration.getInputModes().isEmpty()) {
            return List.copyOf(registration.getInputModes());
        }
        return List.of("text");
    }

    private List<String> resolveOutputModes(Map<String, String> metadata,
                                            DistributedAgentProperties.AgentRegistration registration) {
        if (registration != null && registration.getOutputModes() != null && !registration.getOutputModes().isEmpty()) {
            return List.copyOf(registration.getOutputModes());
        }
        return List.of("text", "json");
    }

    private String resolveCardPath(Map<String, String> metadata,
                                   DistributedAgentProperties.AgentRegistration registration) {
        String metadataValue = metadata == null ? null : metadata.get("agent-card-path");
        if (StringUtils.hasText(metadataValue)) {
            return metadataValue.trim();
        }
        return resolveCardPath(registration);
    }

    private String resolveTaskPath(Map<String, String> metadata,
                                   DistributedAgentProperties.AgentRegistration registration,
                                   String metadataKey,
                                   String fallback) {
        String metadataValue = metadata == null ? null : metadata.get(metadataKey);
        if (StringUtils.hasText(metadataValue)) {
            return metadataValue.trim();
        }
        return StringUtils.hasText(fallback) ? fallback : "/a2a";
    }

    private List<String> parseMetadataList(Map<String, String> metadata, String key) {
        String value = metadata == null ? null : metadata.get(key);
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        String[] parts = value.split(",");
        List<String> results = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part == null ? "" : part.trim();
            if (StringUtils.hasText(trimmed)) {
                results.add(trimmed);
            }
        }
        return List.copyOf(results);
    }

    private String resolveInstanceBaseUrl(ServiceInstance instance) {
        URI uri = instance.getUri();
        if (uri != null) {
            return uri.toString();
        }
        String scheme = StringUtils.hasText(instance.getScheme()) ? instance.getScheme() : "http";
        return scheme + "://" + instance.getHost() + ":" + instance.getPort();
    }

    private boolean shouldRefresh(String agentId) {
        long intervalMillis = Math.max(properties.getRefreshIntervalSeconds(), 1) * 1000L;
        Long lastRefreshedAt = refreshedAt.get(agentId);
        return lastRefreshedAt == null || System.currentTimeMillis() - lastRefreshedAt >= intervalMillis;
    }

    private String resolveBaseUrl(DistributedAgentProperties.AgentRegistration registration) {
        if (registration == null) {
            return null;
        }
        return agentInstanceResolver.resolveBaseUrl(registration)
                .orElse(registration.getBaseUrl());
    }

    private String resolveCardPath(DistributedAgentProperties.AgentRegistration registration) {
        if (registration != null && StringUtils.hasText(registration.getAgentCardPath())) {
            return registration.getAgentCardPath();
        }
        return properties.getAgentCardPath();
    }
}
