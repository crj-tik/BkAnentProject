package com.bkanent.agent.registry;

import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardWrapper;
import com.bkanent.common.agent.AgentCard;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentExtension;
import io.a2a.spec.AgentSkill;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficialAgentCardDiscoveryClientTest {
    private final OfficialAgentCardDiscoveryClient client = new OfficialAgentCardDiscoveryClient(
            new DefaultListableBeanFactory().getBeanProvider(
                    com.alibaba.cloud.ai.a2a.registry.nacos.discovery.NacosAgentCardProvider.class));

    @Test
    void preservesPublishedSkillsCapabilitiesAndProtocolFacts() {
        AgentSkill skill = new AgentSkill("listing.search", "Find listings", "Search actual housing candidates",
                List.of("listing"), List.of("Near metro"), List.of("text"), List.of("application/json"));
        AgentExtension extension = new AgentExtension("explicit skills", Map.of("version", "1"), false,
                "urn:bkagent:skill-selection:v1");
        AgentCard converted = client.convertWrapper(wrapper(new AgentCapabilities(false, true, true,
                List.of(extension)), List.of(skill)));

        assertThat(converted.description()).isEqualTo("published description");
        assertThat(converted.supportedSkills()).containsExactly("listing.search");
        assertThat(converted.skillDescriptors()).singleElement().satisfies(found -> {
            assertThat(found.name()).isEqualTo("Find listings");
            assertThat(found.description()).isEqualTo(skill.description());
            assertThat(found.examples()).containsExactly("Near metro");
        });
        assertThat(converted.supportsStreaming()).isFalse();
        assertThat(converted.supportsAsyncTask()).isNull();
        assertThat(converted.capabilities()).containsEntry("pushNotifications", true)
                .containsEntry("stateTransitionHistory", true);
        assertThat(converted.capabilities().get("extensions")).asList().singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("uri", "urn:bkagent:skill-selection:v1");
        assertThat(converted.preferredTransport()).isEqualTo("JSONRPC");
        assertThat(converted.protocolVersion()).isEqualTo("0.3.0");
    }

    @Test
    void absentCapabilitiesRemainUnknownRatherThanInferredFromTransport() {
        AgentCardWrapper wrapper = mock(AgentCardWrapper.class);
        when(wrapper.preferredTransport()).thenReturn("JSONRPC");
        AgentCard converted = client.convertWrapper(wrapper);
        assertThat(converted.supportsStreaming()).isNull();
        assertThat(converted.supportsAsyncTask()).isNull();
        assertThat(converted.supportedSkills()).isEmpty();
        assertThat(converted.capabilities()).isEmpty();
    }

    @Test
    void rawNacosUnknownFlagsSurviveSdkPrimitiveNormalization() throws Exception {
        var service = mock(com.alibaba.nacos.api.ai.A2aService.class);
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("service", service);
        client.setRawNacosService(beans.getBeanProvider(com.alibaba.nacos.api.ai.A2aService.class));
        var official = new io.a2a.spec.AgentCard.Builder().name("listing-agent").url("http://localhost:9999/a2a")
                .version("1").description("listing").capabilities(new AgentCapabilities(false, false, false, List.of()))
                .skills(List.of()).defaultInputModes(List.of("text")).defaultOutputModes(List.of("text"))
                .supportsAuthenticatedExtendedCard(false).preferredTransport("JSONRPC").protocolVersion("0.2.5").build();
        var raw = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                com.bkanent.common.a2a.NacosSkillCardMapper.toNacos(official), com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo.class);
        raw.getCapabilities().setStreaming(null); raw.getCapabilities().setPushNotifications(null);
        raw.getCapabilities().setStateTransitionHistory(null);
        when(service.getAgentCard("listing-agent")).thenReturn(raw);
        var result = client.fetchByAgentName("listing-agent").orElseThrow();
        assertThat(result.supportsStreaming()).isNull();
        assertThat(result.capabilities()).doesNotContainKeys("streaming", "pushNotifications", "stateTransitionHistory");
        raw.getCapabilities().setStreaming(false);
        assertThat(client.fetchByAgentName("listing-agent").orElseThrow().supportsStreaming()).isFalse();
    }

    private AgentCardWrapper wrapper(AgentCapabilities capabilities, List<AgentSkill> skills) {
        return new AgentCardWrapper(new io.a2a.spec.AgentCard("listing-agent", "published description",
                "http://localhost:9999/a2a", null, "2", null, capabilities,
                List.of("text"), List.of("application/json"), skills, false, Map.of(), List.of(), null,
                List.of(), "JSONRPC", "0.3.0"));
    }
}
