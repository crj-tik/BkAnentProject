package com.bkanent.agent.registry;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.common.agent.AgentCard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicAgentRegistryTest {

    @Test
    void selectsTheAgentHttpInstanceWhenNacosAlsoListsADubboInstance() {
        DiscoveryClient discoveryClient = mock(DiscoveryClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DiscoveryClient> discoveryClientProvider = mock(ObjectProvider.class);
        AgentCardDiscoveryClient cardDiscoveryClient = mock(AgentCardDiscoveryClient.class);
        AgentInstanceResolver instanceResolver = mock(AgentInstanceResolver.class);

        ServiceInstance dubboInstance = serviceInstance(
                "http://172.24.0.15:20884",
                Map.of("dubbo", "2.0.2", "protocol", "dubbo"));
        ServiceInstance agentInstance = serviceInstance(
                "http://interview-service:9014",
                Map.of(
                        "agent-id", "interview-agent",
                        "agent-domains", "interview",
                        "agent-runtime-provider", "official",
                        "agent-card-path", "/.well-known/agent.json",
                        "a2a-path", "/a2a"));

        when(discoveryClientProvider.getIfAvailable()).thenReturn(discoveryClient);
        when(discoveryClient.getServices()).thenReturn(List.of("interview-service"));
        when(discoveryClient.getInstances("interview-service"))
                .thenReturn(List.of(dubboInstance, agentInstance));
        when(cardDiscoveryClient.fetchByAgentName("interview-agent")).thenReturn(Optional.empty());
        when(cardDiscoveryClient.fetchAgentCard(
                "http://interview-service:9014", "/.well-known/agent.json"))
                .thenReturn(Optional.of(interviewCard()));

        DynamicAgentRegistry registry = new DynamicAgentRegistry(
                new DistributedAgentProperties(), cardDiscoveryClient, instanceResolver, discoveryClientProvider);

        List<RegisteredAgentDescriptor> descriptors = registry.listDescriptors();

        assertEquals(1, descriptors.size());
        assertEquals("interview-agent", descriptors.get(0).agentId());
        assertEquals("http://interview-service:9014", descriptors.get(0).baseUrl());
        verify(cardDiscoveryClient).fetchAgentCard(
                "http://interview-service:9014", "/.well-known/agent.json");
        verify(cardDiscoveryClient, never()).fetchAgentCard(
                "http://172.24.0.15:20884", "/.well-known/agent.json");

        // Repeated UI catalog requests must retain the cached card during its refresh interval.
        assertEquals(1, registry.listDescriptors().size());
        assertEquals("interview-agent", registry.getByAgentId("interview-agent").orElseThrow().agentId());
        verify(cardDiscoveryClient).fetchAgentCard(
                "http://interview-service:9014", "/.well-known/agent.json");

        // A service without an active HTTP agent instance must disappear from the strict catalog.
        when(discoveryClient.getInstances("interview-service")).thenReturn(List.of(dubboInstance));
        assertEquals(0, registry.listDescriptors().size());

        // Rediscovery must fetch the card again rather than retain the removed cache timestamp.
        when(discoveryClient.getInstances("interview-service"))
                .thenReturn(List.of(dubboInstance, agentInstance));
        assertEquals(1, registry.listDescriptors().size());
    }

    private ServiceInstance serviceInstance(String url, Map<String, String> metadata) {
        ServiceInstance instance = mock(ServiceInstance.class);
        when(instance.getUri()).thenReturn(URI.create(url));
        when(instance.getMetadata()).thenReturn(metadata);
        return instance;
    }

    private AgentCard interviewCard() {
        return new AgentCard(
                "interview-agent",
                "Interview Agent",
                "AI interview preparation and session support",
                "1.0.0",
                List.of("interview.prep"),
                List.of("interview"),
                true,
                true,
                "http://interview-service:9014/a2a",
                List.of("text", "application/json"),
                List.of("text", "application/json"));
    }
}
