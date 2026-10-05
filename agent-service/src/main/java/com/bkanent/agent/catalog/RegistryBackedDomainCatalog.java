package com.bkanent.agent.catalog;

import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentCard;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Exposes a read-only domain summary; it never supplies routing fallbacks. */
@Component
public class RegistryBackedDomainCatalog implements DomainCatalog {

    private final AgentRegistry agentRegistry;

    public RegistryBackedDomainCatalog(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @Override
    public Set<String> domains() {
        return new LinkedHashSet<>(snapshot().domains());
    }

    @Override
    public List<AgentCard> cards() {
        return snapshot().cards();
    }

    @Override
    public CatalogSnapshot snapshot() {
        List<RegisteredAgentDescriptor> descriptors = agentRegistry.listDescriptors();
        List<AgentCard> cards = new ArrayList<>();
        Set<String> domains = new TreeSet<>();
        for (RegisteredAgentDescriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.agentCard() == null) continue;
            AgentCard card = descriptor.agentCard();
            cards.add(card);
            if (card.supportedDomains() != null) {
                card.supportedDomains().stream().filter(value -> value != null && !value.isBlank())
                        .forEach(domains::add);
            }
        }
        return new CatalogSnapshot(List.copyOf(domains), List.copyOf(cards), SOURCE_REGISTRY);
    }
}
