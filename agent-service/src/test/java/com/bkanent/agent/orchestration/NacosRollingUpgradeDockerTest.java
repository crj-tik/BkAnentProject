package com.bkanent.agent.orchestration;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.model.a2a.*;
import com.bkanent.agent.client.*;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.registry.*;
import com.bkanent.agent.service.*;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.a2a.*;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.SkillPublication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallbackProvider;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Nacos version publication and HTTP clients; old/new servers are deterministic protocol fixtures. */
class NacosRollingUpgradeDockerTest {
    @Test
    void serverUpgradePublishesContractBeforeNewSupervisorCallsAndOldTaskStaysAtOriginalEndpoint() throws Exception {
        String address = System.getenv("BK_AGENT_NACOS_SERVER");
        Assumptions.assumeTrue(address != null, "Docker Nacos not configured");
        var mapper = new ObjectMapper(); var oldSends = new AtomicInteger(); var oldQueries = new AtomicInteger();
        var oldCancels = new AtomicInteger(); var newSends = new AtomicInteger();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + http.getAddress().getPort(), name = "rolling-listing-" + UUID.randomUUID();
        for (String version : List.of("0", "1")) http.createContext("/v" + version + "/a2a", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody()); String method = request.path("method").asText();
            boolean old = "0".equals(version); String state = old ? "working" : "completed";
            if ("tasks/get".equals(method)) { assertThat(old).isTrue(); oldQueries.incrementAndGet(); }
            else if ("tasks/cancel".equals(method)) { assertThat(old).isTrue(); oldCancels.incrementAndGet(); state = "canceled"; }
            else {
                if (old) oldSends.incrementAndGet();
                else {
                    newSends.incrementAndGet();
                    assertThat(request.path("params").path("metadata").path("supervisor").path("skillSelection").path("name").asText())
                            .isEqualTo("listing-search");
                }
            }
            var task = Map.of("kind", "task", "id", old ? "old-accepted" : "new-completed", "contextId", name,
                    "status", Map.of("state", state), "artifacts", List.of(Map.of("artifactId", "a-" + version,
                            "parts", List.of(Map.of("kind", "data", "data", Map.of("summary", "version " + version))))));
            byte[] bytes = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", task));
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        http.start(); var properties = new Properties(); properties.setProperty("serverAddr", address); properties.setProperty("namespace", "public");
        var ai = AiFactory.createAiService(properties);
        var beans = new StaticListableBeanFactory(); beans.addBean("raw", ai);
        var discovery = new OfficialAgentCardDiscoveryClient(beans.getBeanProvider(com.alibaba.cloud.ai.a2a.registry.nacos.discovery.NacosAgentCardProvider.class));
        discovery.setRawNacosService(beans.getBeanProvider(com.alibaba.nacos.api.ai.A2aService.class));
        var endpoints = new ArrayList<AgentEndpoint>();
        try {
            publish(ai, name, "0", base, http.getAddress().getPort(), false, endpoints);
            var reference = new AtomicReference<>(descriptor(name, discovery.fetchByAgentName(name).orElseThrow()));
            assertThat(reference.get().agentCard().version()).isEqualTo("0");
            var registry = mock(AgentRegistry.class); when(registry.listDescriptors()).thenAnswer(invocation -> List.of(reference.get()));
            when(registry.getByAgentId(name)).thenAnswer(invocation -> Optional.of(reference.get()));
            var permissions = mock(AgentPermissionService.class); when(permissions.canInvokeChildAgent(eq("1"), any())).thenReturn(true);
            var client = new OfficialA2aAgentClient(new OfficialA2aResponseNormalizer(mapper));
            var execution = new A2aExecutionService(client, mock(SessionStreamService.class), permissions, new DistributedAgentProperties());
            var catalog = new SupervisorCapabilityCatalog(registry, mock(AgentMcpClient.class), permissions, execution, ToolCallbackProvider.from(), mapper);
            var oldBinding = catalog.snapshot("1", false).get("a2a:" + name);
            assertThatThrownBy(() -> catalog.validateArguments(oldBinding, Map.of("skill", Map.of("name", "listing-search"))))
                    .hasMessage("EXPLICIT_SKILL_UNSUPPORTED");
            assertThat(oldSends).hasValue(0); assertThat(newSends).hasValue(0);
            var oldRequest = new AgentTaskInvokeRequest("rolling-session", "old-child", "old-parent", name, "supervisor-agent", name,
                    null, null, "old accepted work", Map.of("userId", "1"), List.of(), List.of(), "json", "old-call", false);
            client.submitAsync(reference.get(), oldRequest); assertThat(oldSends).hasValue(1);

            // Upgrade the server contract/endpoint first; only then rebuild the Supervisor capability view.
            publish(ai, name, "1", base, http.getAddress().getPort(), true, endpoints);
            reference.set(descriptor(name, discovery.fetchByAgentName(name).orElseThrow()));
            assertThat(reference.get().agentCard().version()).isEqualTo("1");
            assertThat(reference.get().agentCard().a2aEndpoint()).endsWith("/v1/a2a");
            var upgraded = catalog.snapshot("1", false).get("a2a:" + name);
            catalog.validateArguments(upgraded, Map.of("skill", Map.of("name", "listing-search", "version", "1")));
            var context = new ToolContext(Map.of("userId", "1", "runId", "new-parent", "sessionId", "rolling-session", "traceId", name, "callId", "new-call"));
            assertThat(upgraded.callback().call("{\"instruction\":\"new work\",\"skill\":{\"name\":\"listing-search\",\"version\":\"1\"}}", context))
                    .contains("COMPLETED");
            assertThat(newSends).hasValue(1);
            assertThat(client.queryAsyncStatus(reference.get(), "old-accepted").status()).isEqualTo("RUNNING");
            client.cancelAsyncTask(reference.get(), "old-accepted");
            assertThat(oldQueries).hasValue(1); assertThat(oldCancels).hasValue(1); assertThat(oldSends).hasValue(1);
            System.out.println("Rolling upgrade agent=" + name + ", versions=0->1, sends old:1/new:1, old query/cancel original endpoint");
        } finally {
            for (var endpoint : endpoints) ai.deregisterAgentEndpoint(name, endpoint);
            ai.shutdown(); http.stop(0);
        }
    }

    private RegisteredAgentDescriptor descriptor(String name, com.bkanent.common.agent.AgentCard card) {
        return new RegisteredAgentDescriptor(name, card.a2aEndpoint(), "/.well-known/agent.json", "/a2a",
                AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD, card, Map.of());
    }
    private void publish(com.alibaba.nacos.api.ai.A2aService ai, String name, String version, String base, int port,
                         boolean explicit, List<AgentEndpoint> endpoints) throws Exception {
        var card = new AgentCard(); card.setName(name); card.setVersion(version); card.setDescription("rolling fixture " + version);
        card.setProtocolVersion("0.2.5"); card.setUrl(base + "/v" + version + "/a2a"); card.setPreferredTransport("JSONRPC");
        card.setSupportsAuthenticatedExtendedCard(false); card.setDefaultInputModes(List.of("text")); card.setDefaultOutputModes(List.of("application/json"));
        var caps = new AgentCapabilities(); caps.setStreaming(false); caps.setPushNotifications(false); caps.setStateTransitionHistory(false);
        var skill = new AgentSkill(); skill.setId("bk-skill/listing/listing-search"); skill.setName("listing-search"); skill.setDescription("查询候选房源"); skill.setTags(List.of("listing")); card.setSkills(List.of(skill));
        if (explicit) {
            var extension = new AgentExtension(); extension.setUri(SkillPublication.EXTENSION_URI); extension.setRequired(false);
            extension.setParams(Map.of("contractVersion", "1", "owner", "listing", "skills", List.of(Map.of("cardSkillId", skill.getId(), "name", "listing-search",
                    "owner", "listing", "version", "1", "contentHash", "rolling-fixture-hash")))); caps.setExtensions(List.of(extension));
        } else caps.setExtensions(List.of());
        card.setCapabilities(caps);
        var server = new com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties(); server.setAddress("127.0.0.1"); server.setPort(port); server.setMessageUrl("/v" + version + "/a2a");
        new SkillAwareNacosOperationService(ai, new com.alibaba.cloud.ai.a2a.registry.nacos.properties.NacosA2aProperties(), server,
                new com.alibaba.cloud.ai.a2a.registry.nacos.register.NacosA2aRegistryProperties()).registerAgent(NacosSkillCardMapper.toOfficial(card));
        var endpoint = new AgentEndpoint(); endpoint.setAddress("127.0.0.1"); endpoint.setPort(port); endpoint.setVersion(version); endpoint.setPath(server.getMessageUrl()); endpoint.setTransport("JSONRPC");
        endpoints.add(endpoint);
    }
}
