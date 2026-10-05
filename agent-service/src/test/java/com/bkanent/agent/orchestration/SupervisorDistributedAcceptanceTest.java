package com.bkanent.agent.orchestration;

import com.alibaba.cloud.ai.a2a.registry.nacos.discovery.NacosAgentCardProvider;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.model.a2a.*;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.bkanent.agent.client.*;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.graph.node.PersistArtifactsNode;
import com.bkanent.agent.graph.official.*;
import com.bkanent.agent.mapper.*;
import com.bkanent.agent.mcp.HttpAgentMcpClient;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.registry.*;
import com.bkanent.agent.service.*;
import com.bkanent.agent.stream.*;
import com.bkanent.agent.workflow.DbTaskArtifactStore;
import com.bkanent.common.agent.*;
import com.bkanent.common.mcp.*;
import com.bkanent.common.skill.core.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.client.*;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Docker Nacos/MySQL and official HTTP A2A/MCP clients; model decisions are deterministic fixtures. */
class SupervisorDistributedAcceptanceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, AtomicInteger> effects = Map.of("listing", new AtomicInteger(), "compare", new AtomicInteger(), "marketing", new AtomicInteger());

    @Test
    void mixedProtocolsResumePinnedRunAcrossRestartAndAcceptancePauseWithoutRepeatingEffects() throws Exception {
        String address = System.getenv("BK_AGENT_NACOS_SERVER");
        Assumptions.assumeTrue(address != null && System.getenv("BK_AGENT_DB_URL") != null, "Docker acceptance environment not configured");
        var db = new DockerAcceptanceDatabase();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + http.getAddress().getPort();
        String run = "mixed-" + UUID.randomUUID(); String sessionId = "session-" + run;
        http.createContext("/listing/a2a", exchange -> a2a(exchange, "listing", run));
        http.createContext("/marketing/a2a", exchange -> a2a(exchange, "marketing", run));
        http.createContext("/", this::mcp);
        http.start();
        var nacos = new Properties(); nacos.setProperty("serverAddr", address); nacos.setProperty("namespace", "public");
        var ai = AiFactory.createAiService(nacos);
        var naming = NacosFactory.createNamingService(nacos);
        var dynamicMcp = new DynamicMcpClientManager();
        var endpoints = new LinkedHashMap<String, AgentEndpoint>();
        var registered = new LinkedHashMap<String, Instance>();
        var options = new DistributedAgentProperties(); options.setRefreshIntervalSeconds(0); options.setHttpCardFallbackEnabled(false);
        var audit = new SessionEventAuditService(options, db.session.getMapper(AgentEventAuditMapper.class), mapper);
        var events = mock(SessionStreamService.class);
        doAnswer(invocation -> { audit.recordAndEnrich(invocation.getArgument(0)); return null; }).when(events).publish(any());
        var saver = new DatabaseCheckpointSaverFactory(db.session.getMapper(AgentWorkflowCheckpointMapper.class), mapper);
        var store = new OrchestrationStore(db.jdbc, mapper);
        var claims = new ApprovalResumeClaimStore(db.session.getMapper(AgentWorkflowApprovalClaimMapper.class), mapper);
        SupervisorToolLoopGraph factory = null; SupervisorToolLoopRunner runner = null;
        try {
            for (String domain : List.of("listing", "marketing")) {
                String agentId = domain + "-agent";
                var card = card(agentId, domain, base + "/" + domain + "/a2a");
                var server = new com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties();
                server.setAddress("127.0.0.1"); server.setPort(http.getAddress().getPort()); server.setMessageUrl("/" + domain + "/a2a");
                new com.bkanent.common.a2a.SkillAwareNacosOperationService(ai,
                        new com.alibaba.cloud.ai.a2a.registry.nacos.properties.NacosA2aProperties(), server,
                        new com.alibaba.cloud.ai.a2a.registry.nacos.register.NacosA2aRegistryProperties())
                        .registerAgent(com.bkanent.common.a2a.NacosSkillCardMapper.toOfficial(card));
                var endpoint = new AgentEndpoint(); endpoint.setAddress("127.0.0.1"); endpoint.setPort(http.getAddress().getPort());
                endpoint.setPath("/" + domain + "/a2a"); endpoint.setVersion("1"); endpoint.setTransport("JSONRPC");
                ai.registerAgentEndpoint(agentId, endpoint); endpoints.put(agentId, endpoint);
                var instance = new Instance(); instance.setIp("127.0.0.1"); instance.setPort(http.getAddress().getPort());
                instance.setMetadata(Map.of("agent-id", agentId, "agent-card-path", "/.well-known/agent.json", "agent-domain", domain));
                String service = run + "-" + domain; naming.registerInstance(service, instance); registered.put(service, instance);
            }
            DiscoveryClient discovery = new DiscoveryClient() {
                public String description() { return "actual Nacos Naming SDK"; }
                public List<String> getServices() { return List.copyOf(registered.keySet()); }
                public List<ServiceInstance> getInstances(String service) {
                    try { return naming.getAllInstances(service, false).stream().map(instance -> {
                        var result = new DefaultServiceInstance(service + instance.getPort(), service, instance.getIp(), instance.getPort(), false);
                        result.getMetadata().putAll(instance.getMetadata()); return (ServiceInstance) result;
                    }).toList(); } catch (Exception exception) { throw new IllegalStateException(exception); }
                }
            };
            var beans = new StaticListableBeanFactory(); beans.addBean("nacosCard", new NacosAgentCardProvider(ai)); beans.addBean("discovery", discovery); beans.addBean("rawA2a", ai);
            var cardDiscovery = new OfficialAgentCardDiscoveryClient(beans.getBeanProvider(NacosAgentCardProvider.class));
            cardDiscovery.setRawNacosService(beans.getBeanProvider(com.alibaba.nacos.api.ai.A2aService.class));
            var registry = new DynamicAgentRegistry(options, cardDiscovery,
                    registration -> Optional.empty(), beans.getBeanProvider(DiscoveryClient.class));
            var permissions = mock(AgentPermissionService.class);
            when(permissions.canInvokeChildAgent(eq("1"), any())).thenReturn(true);
            when(permissions.canUseMcpTools("1")).thenReturn(true); when(permissions.canReadMcpServer(eq("1"), anyString())).thenReturn(true);
            var client = new OfficialA2aAgentClient(new OfficialA2aResponseNormalizer(mapper));
            var execution = new A2aExecutionService(client, events, permissions, options);
            var mcpClient = new HttpAgentMcpClient(Map.of(), mapper, dynamicMcp);
            dynamicMcp.register(new DynamicMcpConnectionConfig("compare-mcp-server", "STREAMABLE", null, null, null, base));
            dynamicMcp.register(new DynamicMcpConnectionConfig("other-compare", "STREAMABLE", null, null, null, base));
            var catalog = new SupervisorCapabilityCatalog(registry, mcpClient, permissions, execution, ToolCallbackProvider.from(), mapper);
            var capabilities = catalog.snapshot("1", true);
            assertThat(capabilities).containsKeys("a2a:listing-agent", "a2a:marketing-agent", "mcp:compare-mcp-server:compareListings", "mcp:other-compare:compareListings");
            assertThat(capabilities.get("a2a:marketing-agent").callback().getToolDefinition().description()).contains("fixture-hash", "marketing-draft");
            var skillRegistry = new SkillRegistry(new SkillFileLoader(), "");
            var props = new SupervisorOrchestrationProperties(); props.setModelRetries(0);
            var model = mock(SupervisorModelTurn.class); var turns = new AtomicInteger();
            when(model.call(anyList(), anyList())).thenAnswer(invocation -> {
                List<Message> messages = invocation.getArgument(0);
                assertThat(messages.get(0).getText()).contains("listing-compare-marketing-draft", "预算300万");
                int turn = turns.getAndIncrement();
                return switch (turn) {
                    case 0 -> call("question", "request_input", "{\"question\":\"在哪个地区\",\"missingFields\":[\"district\"]}");
                    case 1 -> call("listing", SupervisorCapabilityCatalog.alias("a2a:listing-agent"), "{\"instruction\":\"按实际预算300万与浦东地区找候选房\"}");
                    case 2 -> {
                        assertThat(messages.toString()).contains("101", "102");
                        yield call("compare", SupervisorCapabilityCatalog.alias("mcp:compare-mcp-server:compareListings"), "{\"listingIds\":\"101,102\"}");
                    }
                    case 3 -> call("marketing", SupervisorCapabilityCatalog.alias("a2a:marketing-agent"),
                            "{\"instruction\":\"为实际对比结果中的101写草稿，不发布\",\"context\":{\"listingId\":101},\"skill\":{\"name\":\"marketing-draft\"}}");
                    default -> new AssistantMessage("已依据实际候选房与对比结果生成草稿");
                };
            });
            factory = graph(catalog, model, skillRegistry, store, props, events, saver, db);
            runner = new SupervisorToolLoopRunner(factory, store, props, claims);
            var request = new SupervisorTaskRequest(sessionId, "1", run, run, "预算300万，找两套房对比后写草稿", Map.of("requireApproval", true), "api", false,
                    new SkillSelection("listing-compare-marketing-draft", "1"), null, true);
            assertThat(runner.execute(request).status()).isEqualTo("WAITING_USER_INPUT");
            assertThat(effects.values()).allSatisfy(value -> assertThat(value).hasValue(0));
            runner.close(); factory.close(); props.setAccepting(false);
            factory = graph(catalog, model, skillRegistry, store, props, events, saver, db);
            runner = new SupervisorToolLoopRunner(factory, store, props, claims);
            var pausedRunner = runner;
            assertThatThrownBy(() -> pausedRunner.execute(new SupervisorTaskRequest(sessionId, "1", run + "-new", run, "hello", Map.of(), "api", false)))
                    .hasMessage("SUPERVISOR_ACCEPTANCE_PAUSED");
            var input = new SupervisorTaskRequest(sessionId, "1", run + "-input", run, "浦东", Map.of(), "api", false, null, run, null);
            assertThat(runner.execute(input).status()).isEqualTo("WAITING_USER_APPROVAL");
            assertThat(effects.values()).allSatisfy(value -> assertThat(value).hasValue(0));
            var first = pending(saver, run);
            assertThat(runner.resume(approval(first)).status()).isEqualTo("WAITING_USER_APPROVAL");
            assertThat(effects.get("listing")).hasValue(1); assertThat(effects.get("compare")).hasValue(0);
            runner.close(); factory.close();
            factory = graph(catalog, model, skillRegistry, store, props, events, saver, db);
            runner = new SupervisorToolLoopRunner(factory, store, props, claims);
            assertThat(runner.execute(request).status()).isEqualTo("WAITING_USER_APPROVAL");
            var second = pending(saver, run);
            assertThat(runner.resume(approval(second)).status()).isEqualTo("WAITING_USER_APPROVAL");
            var third = pending(saver, run);
            var completed = runner.resume(approval(third));
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(runner.resume(approval(third))).isEqualTo(completed);
            assertThat(effects.values()).allSatisfy(value -> assertThat(value).hasValue(1));
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_invocation WHERE run_id=? AND remote_association_json IS NOT NULL", Integer.class, run)).isEqualTo(2);
            var artifactRows = db.jdbc.queryForList("SELECT metadata_json FROM agent_task_artifact WHERE task_id=?", run);
            assertThat(artifactRows).hasSize(3).allSatisfy(row -> assertThat(String.valueOf(row.get("metadata_json"))).contains("EXPLICIT_SKILL", "capabilityId", "callId", "listing-compare-marketing-draft"));
            var replay = audit.replay(sessionId, run, null, 0L, 1000);
            assertThat(replay).anySatisfy(event -> assertThat(event.eventType()).isEqualTo("supervisor.waiting_user_input"));
            var resultEvent = replay.stream().filter(event -> "tool.completed".equals(event.eventType())).findFirst().orElseThrow();
            assertThat(resultEvent.metadata()).containsEntry("userId", "1").containsEntry("terminal", false);
            assertThat(audit.replay(sessionId, run, resultEvent.eventId(), null, 1000)).allSatisfy(event -> assertThat(event.sequence()).isGreaterThan(resultEvent.sequence()));
            assertThatThrownBy(() -> runnerStateOwner(store, run)).hasMessage("CONTINUATION_OWNER_MISMATCH");
            dynamicMcp.unregister("compare-mcp-server");
            assertThat(catalog.snapshot("1", true)).doesNotContainKey("mcp:compare-mcp-server:compareListings").containsKey("mcp:other-compare:compareListings");
            System.out.println("Distributed acceptance run=" + run + ", turns=" + turns + ", actual effects=listing:1/compare:1/marketing:1");
        } finally {
            if (runner != null) runner.close(); if (factory != null) factory.close(); dynamicMcp.shutdown();
            for (var entry : registered.entrySet()) naming.deregisterInstance(entry.getKey(), entry.getValue());
            for (var entry : endpoints.entrySet()) ai.deregisterAgentEndpoint(entry.getKey(), entry.getValue());
            naming.shutDown(); ai.shutdown(); http.stop(0);
        }
    }

    private void runnerStateOwner(OrchestrationStore store, String run) { store.assertOwner(run, "2"); }
    private ApprovalRequest pending(DatabaseCheckpointSaverFactory saver, String run) {
        var checkpoint = saver.create(SupervisorToolLoopGraph.RUNNER_VERSION).get(com.alibaba.cloud.ai.graph.RunnableConfig.builder().threadId(run).build()).orElseThrow();
        return mapper.convertValue(checkpoint.getState().get(SupervisorToolLoopGraph.STATE_KEY), ToolLoopState.class).pendingApproval;
    }
    private SupervisorToolLoopGraph graph(SupervisorCapabilityCatalog catalog, SupervisorModelTurn model, SkillRegistry skills, OrchestrationStore store,
                                          SupervisorOrchestrationProperties props, SessionStreamService events, DatabaseCheckpointSaverFactory saver, DockerAcceptanceDatabase db) {
        var graph = new SupervisorToolLoopGraph(catalog, model, skills, store, mapper, props, events, new OfficialSupervisorGraphSchema(), saver);
        graph.setArtifacts(new PersistArtifactsNode(new DbTaskArtifactStore(db.session.getMapper(AgentTaskArtifactMapper.class), mapper), events));
        return graph;
    }
    private ApprovalCallbackRequest approval(ApprovalRequest pending) {
        return new ApprovalCallbackRequest(pending.approvalId(), pending.taskId(), pending.sessionId(), ApprovalStatus.APPROVED, "1", null, pending.traceId(), pending.subjectVersion());
    }
    private AssistantMessage call(String id, String name, String arguments) {
        return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments))).build();
    }
    private com.alibaba.nacos.api.ai.model.a2a.AgentCard card(String id, String domain, String url) {
        var card = new com.alibaba.nacos.api.ai.model.a2a.AgentCard(); card.setName(id); card.setDescription("实际HTTP协议验收 " + domain);
        card.setVersion("1"); card.setProtocolVersion("0.2.5"); card.setUrl(url); card.setPreferredTransport("JSONRPC"); card.setSupportsAuthenticatedExtendedCard(false);
        card.setDefaultInputModes(List.of("text", "application/json")); card.setDefaultOutputModes(List.of("text", "application/json"));
        var capabilities = new AgentCapabilities(); capabilities.setStreaming(false); capabilities.setPushNotifications(false); capabilities.setStateTransitionHistory(false);
        var skill = new AgentSkill(); String name = "marketing".equals(domain) ? "marketing-draft" : "listing-search";
        skill.setId("bk-skill/" + domain + "/" + name); skill.setName(name); skill.setDescription("验收发布技能"); skill.setTags(List.of(domain)); card.setSkills(List.of(skill));
        var extension = new AgentExtension(); extension.setUri(SkillPublication.EXTENSION_URI); extension.setRequired(false);
        extension.setParams(Map.of("contractVersion", "1", "owner", domain, "skills", List.of(Map.of("cardSkillId", skill.getId(), "name", name, "owner", domain, "version", "1", "contentHash", "fixture-hash"))));
        capabilities.setExtensions(List.of(extension)); card.setCapabilities(capabilities); return card;
    }
    private void a2a(HttpExchange exchange, String domain, String run) throws IOException {
        var request = mapper.readTree(exchange.getRequestBody()); var metadata = request.path("params").path("metadata").path("supervisor");
        assertThat(metadata.path("parentRunId").asText()).isEqualTo(run); assertThat(metadata.path("callId").asText()).isEqualTo(domain);
        assertThat(metadata.path("parentSkill").path("name").asText()).isEqualTo("listing-compare-marketing-draft");
        if ("marketing".equals(domain)) assertThat(metadata.path("skillSelection").path("name").asText()).isEqualTo("marketing-draft");
        else assertThat(metadata.has("skillSelection")).isFalse();
        effects.get(domain).incrementAndGet();
        Map<String,Object> output = "listing".equals(domain) ? Map.of("summary", "实际候选房", "contentType", "listing_result", "listingIds", List.of(101, 102))
                : Map.of("summary", "实际草稿", "contentType", "copy_draft", "listingId", 101, "draftText", "根据实际房源资料撰写的验收草稿");
        respond(exchange, Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of("kind", "task", "id", "remote-" + domain + "-" + run,
                "contextId", run, "status", Map.of("state", "completed"), "artifacts", List.of(Map.of("artifactId", "artifact-" + domain + "-" + run,
                        "parts", List.of(Map.of("kind", "data", "data", output)))))));
    }
    private void mcp(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
        var request = mapper.readTree(exchange.getRequestBody());
        if (!request.has("id")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
        Object result = switch (request.path("method").asText()) {
            case "initialize" -> Map.of("protocolVersion", request.path("params").path("protocolVersion").asText(), "capabilities", Map.of("tools", Map.of("listChanged", false)), "serverInfo", Map.of("name", "actual-compare-fixture", "version", "1"));
            case "tools/list" -> Map.of("tools", List.of(Map.of("name", "compareListings", "description", "实际比较候选房源ID", "inputSchema",
                    Map.of("type", "object", "properties", Map.of("listingIds", Map.of("type", "string")), "required", List.of("listingIds"), "additionalProperties", false))));
            case "tools/call" -> {
                assertThat(request.path("params").path("arguments").path("listingIds").asText()).isEqualTo("101,102");
                effects.get("compare").incrementAndGet();
                yield Map.of("content", List.of(Map.of("type", "text", "text", "101适合预算，102作为备选")), "structuredContent", Map.of("listingIds", List.of(101,102), "recommendedListingId", 101), "isError", false);
            }
            default -> Map.of();
        };
        respond(exchange, Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
    }
    private void respond(HttpExchange exchange, Object response) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(response); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
}
