package com.bkanent.agent.catalog;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.registry.AgentDescriptorSource;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistryBackedDomainCatalogTest {

    @Test
    void coldStartMustUseFallbackVocabularyBeforeFirstRegistryResult() {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.listDescriptors()).thenReturn(List.of());
        RegistryBackedDomainCatalog catalog = new RegistryBackedDomainCatalog(registry, properties(7, 16));

        DomainCatalog.CatalogSnapshot snapshot = catalog.snapshot();

        assertThat(snapshot.vocabularySource()).isEqualTo(DomainCatalog.SOURCE_COLD_START_FALLBACK);
        assertThat(snapshot.domains()).containsExactly(
                "contract", "listing", "marketing", "media", "notification", "settlement", "trade");
        assertThat(catalog.contains("compare")).isFalse();
        assertThat(catalog.contains("listing")).isTrue();
    }

    @Test
    void firstNonEmptyRegistryResultMustTakeOverAndRetireFallback() {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.listDescriptors()).thenReturn(List.of(
                descriptor("compare-agent", List.of("compare"), List.of("compare.listings")),
                descriptor("listing-agent", List.of("listing"), List.of("listing.search"))
        ));
        RegistryBackedDomainCatalog catalog = new RegistryBackedDomainCatalog(registry, properties(7, 16));

        DomainCatalog.CatalogSnapshot first = catalog.snapshot();
        assertThat(first.vocabularySource()).isEqualTo(DomainCatalog.SOURCE_REGISTRY);
        assertThat(first.domains()).containsExactly("compare", "listing");

        when(registry.listDescriptors()).thenReturn(List.of(
                descriptor("compare-agent", List.of("compare"), List.of("compare.listings"))));
        DomainCatalog.CatalogSnapshot second = catalog.snapshot();
        assertThat(second.vocabularySource()).isEqualTo(DomainCatalog.SOURCE_REGISTRY);
        assertThat(second.domains()).containsExactly("compare");
        assertThat(catalog.contains("listing")).isFalse();
    }

    @Test
    void registryReadyMustStayRegistryEvenWhenRegistryBecomesEmpty() {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.listDescriptors()).thenReturn(List.of(
                descriptor("listing-agent", List.of("listing"), List.of("listing.search"))));
        RegistryBackedDomainCatalog catalog = new RegistryBackedDomainCatalog(registry, properties(7, 16));
        assertThat(catalog.snapshot().vocabularySource()).isEqualTo(DomainCatalog.SOURCE_REGISTRY);

        when(registry.listDescriptors()).thenReturn(List.of());
        DomainCatalog.CatalogSnapshot empty = catalog.snapshot();

        assertThat(empty.vocabularySource()).isEqualTo(DomainCatalog.SOURCE_REGISTRY);
        assertThat(empty.domains()).isEmpty();
    }

    @Test
    void defaultIntentMustFollowMetadataThenConfigThenSkillsFallback() {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.listDescriptors()).thenReturn(List.of(
                new RegisteredAgentDescriptor("compare-agent", "http://localhost", "/.well-known/agent.json",
                        "/a2a", AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD,
                        card("compare-agent", List.of("compare"), List.of("compare.rank", "compare.listings")),
                        Map.of("agent-default-intent", "compare.rank")),
                descriptor("media-agent", List.of("media"), List.of("media.unused")),
                descriptor("other-agent", List.of("other"), List.of("other.first_skill"))
        ));
        RegistryBackedDomainCatalog catalog = new RegistryBackedDomainCatalog(registry, properties(7, 16));

        assertThat(catalog.resolveDefaultIntent("compare")).isEqualTo("compare.rank");
        assertThat(catalog.resolveDefaultIntent("media")).isEqualTo("media.generate_video_task");
        assertThat(catalog.resolveDefaultIntent("other")).isEqualTo("other.first_skill");
        assertThat(catalog.resolveDefaultIntent("unknown")).isNull();
        assertThat(catalog.resolveDefaultIntent("")).isNull();
    }

    @Test
    void rewriteHintMustApplyConfiguredRewritesOnly() {
        RegistryBackedDomainCatalog catalog = new RegistryBackedDomainCatalog(
                mock(AgentRegistry.class), properties(7, 16));

        assertThat(catalog.rewriteHint("settlement.batch")).isEqualTo("settlement.prepare");
        assertThat(catalog.rewriteHint("notification.send")).isEqualTo("notification.send");
        assertThat(catalog.rewriteHint("")).isEqualTo("");
    }

    @Test
    void startMustFailWhenMaxParallelDomainsExceedsBranchCapacity() {
        assertThatThrownBy(() -> new RegistryBackedDomainCatalog(
                mock(AgentRegistry.class), properties(17, 16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-parallel-domains");
    }

    @Test
    void domainsMustBeSortedStablyAcrossCalls() {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.listDescriptors()).thenReturn(List.of(
                descriptor("b-agent", List.of("zebra", "alpha"), List.of()),
                descriptor("a-agent", List.of("mid"), List.of())
        ));
        RegistryBackedDomainCatalog catalog = new RegistryBackedDomainCatalog(registry, properties(7, 16));

        assertThat(catalog.domains()).containsExactly("alpha", "mid", "zebra");
        assertThat(catalog.domains()).containsExactly("alpha", "mid", "zebra");
    }

    private DistributedAgentProperties properties(int maxParallelDomains, int branchCapacity) {
        DistributedAgentProperties properties = new DistributedAgentProperties();
        properties.getPlanning().setMaxParallelDomains(maxParallelDomains);
        properties.getCatalog().setBranchCapacity(branchCapacity);
        return properties;
    }

    private RegisteredAgentDescriptor descriptor(String agentId, List<String> domains, List<String> skills) {
        return new RegisteredAgentDescriptor(agentId, "http://localhost", "/.well-known/agent.json",
                "/a2a", AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD,
                card(agentId, domains, skills), Map.of());
    }

    private AgentCard card(String agentId, List<String> domains, List<String> skills) {
        return new AgentCard(agentId, agentId, agentId + " description", "1.0.0",
                skills, domains, false, false, "http://localhost/a2a",
                List.of("text"), List.of("text"));
    }
}
