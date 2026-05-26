package com.bkanent.agent.registry;

import com.bkanent.agent.config.DistributedAgentProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Component
public class DiscoveryClientAgentInstanceResolver implements AgentInstanceResolver {

    private final ObjectProvider<DiscoveryClient> discoveryClientProvider;

    public DiscoveryClientAgentInstanceResolver(ObjectProvider<DiscoveryClient> discoveryClientProvider) {
        this.discoveryClientProvider = discoveryClientProvider;
    }

    @Override
    public Optional<String> resolveBaseUrl(DistributedAgentProperties.AgentRegistration registration) {
        if (registration == null || !StringUtils.hasText(registration.getServiceId())) {
            return Optional.empty();
        }
        DiscoveryClient discoveryClient = discoveryClientProvider.getIfAvailable();
        if (discoveryClient == null) {
            return Optional.empty();
        }
        List<ServiceInstance> instances = discoveryClient.getInstances(registration.getServiceId());
        if (instances == null || instances.isEmpty()) {
            return Optional.empty();
        }
        ServiceInstance instance = instances.get(0);
        if (instance.getUri() == null) {
            return Optional.empty();
        }
        return Optional.of(instance.getUri().toString());
    }
}
