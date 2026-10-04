package com.bkanent.agent.registry;

import com.alibaba.cloud.ai.a2a.registry.nacos.discovery.NacosAgentCardProvider;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.model.a2a.*;
import com.bkanent.agent.client.OfficialA2aAgentClient;
import com.bkanent.agent.client.OfficialA2aResponseNormalizer;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Real Nacos Agent Registry + HTTP official A2A client; deterministic protocol fixture, not an LLM evaluation. */
class NacosAgentRegistryDockerTest {
    @Test
    void publishesDiscoversEndpointAndInvokesOfficialProtocol() throws Exception {
        String address = System.getenv("BK_AGENT_NACOS_SERVER");
        Assumptions.assumeTrue(address != null && !address.isBlank(), "Docker Nacos is not configured");
        var mapper = new ObjectMapper(); var effects = new AtomicInteger();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = http.getAddress().getPort();
        var card = new AgentCard();
        String name = "acceptance-listing-" + UUID.randomUUID();
        card.setName(name); card.setDescription("真实 Nacos 注册链路验收"); card.setVersion("1.0.0"); card.setProtocolVersion("0.2.5");
        card.setUrl("http://127.0.0.1:" + port + "/a2a"); card.setPreferredTransport("JSONRPC");
        card.setSupportsAuthenticatedExtendedCard(false);
        card.setDefaultInputModes(List.of("text", "application/json")); card.setDefaultOutputModes(List.of("text", "application/json"));
        var capabilities = new AgentCapabilities(); capabilities.setStreaming(false); capabilities.setPushNotifications(false);
        capabilities.setStateTransitionHistory(false); capabilities.setExtensions(List.of()); card.setCapabilities(capabilities);
        var skill = new AgentSkill(); skill.setId("listing-search"); skill.setName("找房"); skill.setDescription("检索候选房源"); skill.setTags(List.of("listing"));
        card.setSkills(List.of(skill));
        http.createContext("/.well-known/agent.json", exchange -> {
            byte[] bytes = mapper.writeValueAsBytes(card); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        http.createContext("/a2a", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody()); effects.incrementAndGet();
            Map<String, Object> result = Map.of("kind", "task", "id", "remote-accepted", "contextId", "acceptance",
                    "status", Map.of("state", "completed"), "artifacts", List.of(Map.of("artifactId", "remote-artifact", "parts",
                            List.of(Map.of("kind", "data", "data", Map.of("summary", "真实协议调用完成", "listingIds", List.of(101)))))));
            byte[] bytes = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        http.start();
        Properties properties = new Properties(); properties.setProperty("serverAddr", address); properties.setProperty("namespace", "public");
        var ai = AiFactory.createAiService(properties);
        var endpoint = new AgentEndpoint(); endpoint.setAddress("127.0.0.1"); endpoint.setPort(port); endpoint.setPath("/a2a");
        endpoint.setVersion("1.0.0"); endpoint.setTransport("JSONRPC");
        try {
            ai.releaseAgentCard(card);
            ai.registerAgentEndpoint(name, endpoint);
            assertThat(new NacosAgentCardProvider(ai).getAgentCard(name).url()).isEqualTo(card.getUrl());
            var beans = new StaticListableBeanFactory(); beans.addBean("nacos", new NacosAgentCardProvider(ai));
            var discovery = new OfficialAgentCardDiscoveryClient(beans.getBeanProvider(NacosAgentCardProvider.class));
            var actual = discovery.fetchByAgentName(name).orElseThrow();
            assertThat(actual.a2aEndpoint()).isEqualTo(card.getUrl());
            assertThat(actual.description()).isEqualTo(card.getDescription());
            assertThat(actual.skillDescriptors().get(0).description()).isEqualTo(skill.getDescription());
            assertThat(discovery.fetchAgentCard("http://127.0.0.1:" + port, "/.well-known/agent.json")).isPresent();
            var descriptor = new RegisteredAgentDescriptor("listing-agent", "http://127.0.0.1:" + port,
                    "/.well-known/agent.json", "/a2a", AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD, actual, Map.of());
            var client = new OfficialA2aAgentClient(new OfficialA2aResponseNormalizer(mapper));
            var response = client.invoke(descriptor, new AgentTaskInvokeRequest("session", "child", "run", "trace", "supervisor-agent", "listing-agent",
                    null, null, "查询房源", Map.of("userId", "1"), List.of(), List.of(), "json", "call", false));
            assertThat(response.status()).isEqualTo("COMPLETED"); assertThat(response.artifactIds()).contains("remote-artifact");
            assertThat(effects).hasValue(1);
        } finally { ai.deregisterAgentEndpoint(name, endpoint); ai.shutdown(); http.stop(0); }
    }
}
