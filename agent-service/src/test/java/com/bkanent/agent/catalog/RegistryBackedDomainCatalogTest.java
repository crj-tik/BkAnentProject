package com.bkanent.agent.catalog;

import com.bkanent.agent.registry.AgentDescriptorSource;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistryBackedDomainCatalogTest {

    @Test
    void emptyRegistryMustNotInventFallbackDomainsOrAgents() {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.listDescriptors()).thenReturn(List.of());

        DomainCatalog.CatalogSnapshot snapshot = new RegistryBackedDomainCatalog(registry).snapshot();

        assertThat(snapshot.vocabularySource()).isEqualTo(DomainCatalog.SOURCE_REGISTRY);
        assertThat(snapshot.domains()).isEmpty();
        assertThat(snapshot.cards()).isEmpty();
    }

    @Test
    void domainsMustComeFromCurrentRegistryAndStaySorted() {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.listDescriptors()).thenReturn(List.of(
                descriptor("b-agent", List.of("zebra", "alpha")),
                descriptor("a-agent", List.of("mid"))));
        RegistryBackedDomainCatalog catalog = new RegistryBackedDomainCatalog(registry);

        assertThat(catalog.domains()).containsExactly("alpha", "mid", "zebra");
        assertThat(catalog.snapshot().cards()).extracting(AgentCard::agentId)
                .containsExactly("b-agent", "a-agent");
    }

    private RegisteredAgentDescriptor descriptor(String agentId, List<String> domains) {
        AgentCard card = new AgentCard(agentId, agentId, agentId + " description", "1.0.0",
                List.of(), domains, false, false, "http://localhost/a2a", List.of("text"), List.of("text"));
        return new RegisteredAgentDescriptor(agentId, "http://localhost", "/.well-known/agent.json",
                "/a2a", AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD, card, Map.of());
    }
}
