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
                    List<String> errors = OfficialA2aRegistrationValidator.validate(
                            registration, properties.getAgentCardPath());
                    if (!errors.isEmpty()) {
                        throw new IllegalArgumentException(String.join("; ", errors));
                    }
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
                        () -> descriptors.remove(agentId));
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
            agentCardDiscoveryClient.fetchByAgentName(agentId)
                    .or(() -> agentCardDiscoveryClient.fetchAgentCard(baseUrl, cardPath))
                    .map(card -> buildDiscoveredDescriptor(serviceId, registration, metadata, baseUrl, cardPath, card))
                    .ifPresentOrElse(descriptor -> descriptors.put(agentId, descriptor),
                            () -> descriptors.remove(agentId));
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
        String a2aPath = StringUtils.hasText(registration.getA2aPath()) ? registration.getA2aPath() : "/a2a";
        RegisteredAgentDescriptor descriptor = new RegisteredAgentDescriptor(
                registration.getAgentId(),
                baseUrl,
                resolveCardPath(registration),
                a2aPath,
                resolveRuntimeType(Map.of(), registration, resolveCardPath(registration), joinUrl(baseUrl, a2aPath)),
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
                        joinUrl(baseUrl, a2aPath),
                        List.copyOf(registration.getInputModes()),
                        List.copyOf(registration.getOutputModes())
                )
        );
        OfficialA2aRegistrationValidator.requireValidDescriptor(descriptor);
        return descriptor;
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
        String resolvedPath = registration == null || !StringUtils.hasText(registration.getA2aPath())
                ? "/a2a" : registration.getA2aPath();
        if (StringUtils.hasText(endpoint) && StringUtils.hasText(baseUrl) && endpoint.startsWith(baseUrl)) {
            resolvedPath = endpoint.substring(baseUrl.length());
        }
        String agentId = resolveAgentId(serviceId, metadata, registration);
        RegisteredAgentDescriptor descriptor = new RegisteredAgentDescriptor(
                agentId,
                baseUrl,
                cardPath,
                resolvedPath,
                resolveRuntimeType(metadata, registration, cardPath, agentCard.a2aEndpoint()),
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
        OfficialA2aRegistrationValidator.requireValidDescriptor(descriptor);
        return descriptor;
    }

    private boolean resolveBoolean(Boolean discoveredValue, boolean fallbackValue) {
        return discoveredValue != null ? discoveredValue : fallbackValue;
    }

    private AgentRuntimeType resolveRuntimeType(Map<String, String> metadata,
                                                DistributedAgentProperties.AgentRegistration registration,
                                                String cardPath,
                                                String endpoint) {
        String provider = metadata == null ? null : metadata.get("agent-runtime-provider");
        if (!StringUtils.hasText(provider) && registration != null) {
            provider = registration.getRuntimeProvider();
        }
        String normalized = StringUtils.hasText(provider) ? provider.trim().toLowerCase() : "official";
        if ("custom".equals(normalized) || "custom_http".equals(normalized)) {
            throw new IllegalArgumentException("agent " + resolveAgentId(null, metadata, registration)
                    + " declares unsupported custom HTTP runtime; Alibaba official A2A is required");
        }
        if (!"official".equals(normalized) && !"auto".equals(normalized)) {
            throw new IllegalArgumentException("unsupported A2A runtime provider: " + normalized);
        }
        if (!StringUtils.hasText(cardPath) || !cardPath.contains("/.well-known/agent.json")) {
            throw new IllegalArgumentException("official A2A Agent Card path is required");
        }
        if (!StringUtils.hasText(endpoint)) {
            throw new IllegalArgumentException("official A2A endpoint is required in the Agent Card");
        }
        return AgentRuntimeType.ALIBABA_A2A;
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

    private String joinUrl(String baseUrl, String path) {
        if (!StringUtils.hasText(baseUrl)) {
            return path;
        }
        return baseUrl.endsWith("/") && path.startsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + path
                : baseUrl + path;
    }
}
