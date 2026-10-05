package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.properties.NacosA2aProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.register.NacosA2aRegistryProperties;
import com.alibaba.cloud.ai.a2a.registry.nacos.service.NacosA2aOperationService;
import com.alibaba.nacos.api.ai.AiFactory;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillFileLoader;
import com.bkanent.common.skill.core.SkillRegistry;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentCapabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Nacos verifies that republishing the same Agent version replaces skill hashes and deletions. */
class LiveSkillAgentCardsDockerTest {
    @Test
    void registryReloadRepublishesSameAgentVersionToRealNacos() throws Exception {
        String address = System.getenv("BK_AGENT_NACOS_SERVER");
        Assumptions.assumeTrue(address != null && System.getenv("BK_AGENT_NACOS_PASSWORD") != null, "Docker Nacos update credentials not configured");
        Properties configuration = new Properties();
        configuration.setProperty("serverAddr", address); configuration.setProperty("namespace", "public");
        configuration.setProperty("username", System.getenv("BK_AGENT_NACOS_USER"));
        configuration.setProperty("password", System.getenv("BK_AGENT_NACOS_PASSWORD"));
        var ai = AiFactory.createAiService(configuration);
        var maintainer = com.alibaba.nacos.maintainer.client.ai.AiMaintainerFactory.createAiMaintainerService(configuration);
        var server = new A2aServerProperties(); server.setAddress("127.0.0.1"); server.setPort(19099); server.setMessageUrl("/a2a");
        var registration = new SkillAwareNacosOperationService(ai, new NacosA2aProperties(), server, new NacosA2aRegistryProperties(), () -> maintainer);
        SkillFileLoader loader = mock(SkillFileLoader.class);
        var old = skill("find", "old");
        when(loader.loadAll()).thenReturn(List.of(old));
        var registry = new SkillRegistry(loader, "");
        String name = "hot-skill-acceptance-" + UUID.randomUUID();
        var initial = SkillAgentCardPublisher.publish(new AgentCard.Builder().name(name).description("hot reload test")
                .url("http://127.0.0.1:19099/a2a").version("1").protocolVersion("0.2.5").preferredTransport("JSONRPC")
                .supportsAuthenticatedExtendedCard(false).capabilities(new AgentCapabilities(false, false, false, List.of()))
                .skills(List.of()).defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).build(), registry, "listing");
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("card", initial); beans.registerSingleton("nacos", registration);
        var live = new LiveSkillAgentCards(registry, beans.getBeanProvider(AgentCard.class), beans.getBeanProvider(NacosA2aOperationService.class));
        try {
            registration.registerAgent(initial);
            live.afterSingletonsInstantiated();
            var changed = skill("find", "changed");
            when(loader.loadAll()).thenReturn(List.of(changed, skill("new", "new")));
            registry.reload(); live.publishPending();
            var actual = awaitCard(ai, name, live.currentCard(initial));
            assertThat(actual.version()).isEqualTo("1");
            assertThat(actual.skills()).extracting(io.a2a.spec.AgentSkill::name).containsExactly("find", "new");
            assertThat(actual.capabilities().extensions()).isEqualTo(live.currentCard(initial).capabilities().extensions());
            assertThat(actual.capabilities().extensions().toString()).contains(changed.contentHash()).doesNotContain(old.contentHash());
            when(loader.loadAll()).thenReturn(List.of());
            registry.reload(); live.publishPending();
            actual = awaitCard(ai, name, live.currentCard(initial));
            assertThat(actual.skills()).isEmpty();
            assertThat(actual.capabilities().extensions()).isEqualTo(live.currentCard(initial).capabilities().extensions());
        } finally {
            live.destroy();
            var endpoint = new com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint();
            endpoint.setAddress("127.0.0.1"); endpoint.setPort(19099); endpoint.setPath("/a2a"); endpoint.setTransport("JSONRPC"); endpoint.setVersion("1");
            try { ai.deregisterAgentEndpoint(name, endpoint); maintainer.deleteAgent(name, "public"); } finally { ai.shutdown(); }
        }
    }

    private SkillDefinition skill(String name, String body) {
        return SkillDefinition.builder().name(name).description(name).domain("listing").systemPrompt(body).build();
    }

    private AgentCard awaitCard(com.alibaba.nacos.api.ai.A2aService service, String name, AgentCard expected) throws Exception {
        AgentCard actual = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            actual = NacosSkillCardMapper.toOfficial(service.getAgentCard(name));
            if (actual.capabilities().extensions().equals(expected.capabilities().extensions())) return actual;
            Thread.sleep(100);
        }
        assertThat(actual.capabilities().extensions()).isEqualTo(expected.capabilities().extensions());
        return actual;
    }
}
