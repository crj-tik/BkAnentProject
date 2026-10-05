package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.properties.NacosA2aProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.register.NacosA2aRegistryProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.service.NacosA2aOperationService;
import com.alibaba.nacos.api.ai.A2aService;
import com.bkanent.common.agent.SkillPublication;
import io.a2a.spec.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NacosSkillCardMapperTest {
    private AgentCard card() {
        return new AgentCard.Builder().name("listing-agent").description("actual Card").url("http://localhost:8001/a2a").version("1")
                .preferredTransport("JSONRPC").protocolVersion("0.2.5").supportsAuthenticatedExtendedCard(false)
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("application/json"))
                .skills(List.of(new AgentSkill("local", "local", "find", List.of("listing"), List.of(), List.of(), List.of())))
                .capabilities(new AgentCapabilities(false, false, false, List.of(new AgentExtension("exact skill metadata",
                        Map.of("contractVersion", "1", "skills", List.of(Map.of("name", "local", "version", "1", "contentHash", "hash"))),
                        false, SkillPublication.EXTENSION_URI)))).build();
    }
    @Test
    void roundTripKeepsExactExtensionsAndHandlesOptionalMissingBooleans() {
        var raw = NacosSkillCardMapper.toNacos(card());
        assertThat(raw.getCapabilities().getExtensions()).hasSize(1);
        raw.getCapabilities().setPushNotifications(null); raw.getCapabilities().setStateTransitionHistory(null);
        raw.setSupportsAuthenticatedExtendedCard(null);
        var returned = NacosSkillCardMapper.toOfficial(raw);
        assertThat(returned.capabilities().extensions()).isEqualTo(card().capabilities().extensions());
        assertThat(returned.skills()).isEqualTo(card().skills());
        assertThat(raw.getCapabilities().getPushNotifications()).isNull();
    }
    @Test
    void autoConfigurationReplacesOnlyEnabledRegistrationAndPreservesLatestPolicy() {
        var service = mock(A2aService.class);
        var nacos = new NacosA2aProperties(); var server = new A2aServerProperties(); var registry = new NacosA2aRegistryProperties();
        server.setAddress("127.0.0.1"); server.setPort(8001); server.setMessageUrl("/a2a"); registry.setRegisterAsLatest(false);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SkillNacosCompatibilityAutoConfiguration.class))
                .withBean(A2aService.class, () -> service).withBean(NacosA2aProperties.class, () -> nacos)
                .withBean(A2aServerProperties.class, () -> server).withBean(NacosA2aRegistryProperties.class, () -> registry)
                .withBean(NacosA2aOperationService.class, () -> new NacosA2aOperationService(service, nacos, server, registry))
                .run(context -> {
                    assertThat(context).hasSingleBean(NacosA2aOperationService.class);
                    assertThat(context.getBean(NacosA2aOperationService.class)).isInstanceOf(SkillAwareNacosOperationService.class);
                    context.getBean(NacosA2aOperationService.class).registerAgent(card());
                    var capture = org.mockito.ArgumentCaptor.forClass(com.alibaba.nacos.api.ai.model.a2a.AgentCard.class);
                    verify(service).releaseAgentCard(capture.capture(), eq("SERVICE"), eq(false));
                    assertThat(capture.getValue().getCapabilities().getExtensions()).hasSize(1);
                    verify(service).registerAgentEndpoint(eq("listing-agent"), any());
                });
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SkillNacosCompatibilityAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(NacosA2aOperationService.class));
    }
}
