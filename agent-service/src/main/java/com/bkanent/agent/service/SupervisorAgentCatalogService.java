package com.bkanent.agent.service;

import com.bkanent.agent.model.distributed.AgentCatalogView;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class SupervisorAgentCatalogService {

    private final AgentRegistry agentRegistry;

    public SupervisorAgentCatalogService(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    public List<AgentCatalogView> listCatalog() {
        return agentRegistry.listDescriptors().stream()
                .sorted(Comparator.comparing(RegisteredAgentDescriptor::agentId, String.CASE_INSENSITIVE_ORDER))
                .map(this::toView)
                .toList();
    }

    private AgentCatalogView toView(RegisteredAgentDescriptor descriptor) {
        return new AgentCatalogView(
                descriptor.agentId(),
                descriptor.agentCard() == null ? null : descriptor.agentCard().name(),
                descriptor.agentCard() == null ? null : descriptor.agentCard().description(),
                descriptor.baseUrl(),
                descriptor.agentCard() == null ? null : descriptor.agentCard().a2aEndpoint(),
                descriptor.runtimeType() == null ? null : descriptor.runtimeType().name(),
                descriptor.officialPayloadMode(),
                descriptor.source() == null ? null : descriptor.source().name(),
                descriptor.agentCard() == null ? List.of() : descriptor.agentCard().supportedDomains(),
                descriptor.agentCard() == null ? List.of() : descriptor.agentCard().supportedSkills(),
                descriptor.agentCard() == null ? null : descriptor.agentCard().supportsStreaming(),
                descriptor.agentCard() == null ? null : descriptor.agentCard().supportsAsyncTask()
        );
    }
}
