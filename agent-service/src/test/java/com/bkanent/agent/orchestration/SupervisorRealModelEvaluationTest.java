package com.bkanent.agent.orchestration;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.bkanent.agent.graph.official.*;
import com.bkanent.agent.model.distributed.*;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.*;
import com.bkanent.agent.registry.*;
import com.bkanent.agent.service.*;
import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.model.*;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.*;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real inference through the production one-turn adapter and graph; business data are fixtures.
 * Semantic quality is reported for review, never converted to a business ordering guard. */
class SupervisorRealModelEvaluationTest {
    @Test
    void recordActualModelDecisionsAndQualityDeviationsWithoutAStepExecutor() throws Exception {
        String base = System.getenv("BK_AGENT_EVAL_MODEL_URL"), modelName = System.getenv("BK_AGENT_EVAL_MODEL_NAME");
        Assumptions.assumeTrue(base != null && modelName != null, "Real model evaluation not configured");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var api = DeepSeekApi.builder().baseUrl(base).completionsPath("/v1/chat/completions").apiKey("local-evaluation").build();
        var actual = DeepSeekChatModel.builder().deepSeekApi(api).retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(DeepSeekChatOptions.builder()
                .model(modelName).temperature(0.0).maxTokens(1200).internalToolExecutionEnabled(false).build()).build();
        var productionSkills = new SkillRegistry(new SkillFileLoader(), "");
        var ordinary = SkillDefinition.builder().name("contract-review").description("理解合同文本、识别条款风险并委托合同 Agent 审阅的普通指引")
                .domain("supervisor").capabilities(new com.bkanent.common.skill.SkillCapabilityPolicy("allowlist", List.of("a2a:contract-agent")))
                .systemPrompt("先理解提供的合同原文与审阅目的；仅委托 contract Agent 分析已提供的条款，不发送通知；最终引用实际分析结果。").build();
        var skills = mock(SkillRegistry.class);
        when(skills.findOperationalSkills("supervisor")).thenReturn(List.of(ordinary));
        when(skills.findSupervisorSkills()).thenReturn(productionSkills.findSupervisorSkills());
        when(skills.getByName(anyString())).thenAnswer(invocation -> "contract-review".equals(invocation.getArgument(0)) ? ordinary
                : productionSkills.getByName(invocation.getArgument(0)));
        var reports = new ArrayList<Map<String, Object>>();
        List<EvaluationCase> cases = List.of(
                new EvaluationCase("greeting", "你好，只打个招呼，不需要查询。", false, null),
                new EvaluationCase("negation-contract", "不要发送通知，只分析这份合同：买方逾期一天即没收全部定金，卖方逾期不承担责任。指出条款风险。", false, null),
                new EvaluationCase("auto-direct", "请委托合同 Agent 审阅以下原文并汇总实际结果：买方逾期一天即没收全部定金，卖方逾期不承担责任。不发送通知。", false, null),
                new EvaluationCase("auto-load", "请先使用目录中合同审阅指引理解任务，再审阅：双方交付延迟的责任应如何界定？不要通知他人。", false, null),
                new EvaluationCase("explicit-full-1", "预算300万，在上海浦东找两套两居房对比。根据实际对比选总价较低的一套，为首次置业家庭写小红书草稿，只写草稿不发布。", true, null),
                new EvaluationCase("explicit-full-2", "预算300万，在上海浦东找两套两居房对比。根据实际对比选总价较低的一套，为首次置业家庭写小红书草稿，只写草稿不发布。", true, null),
                new EvaluationCase("explicit-missing", "帮我找两套房对比，然后写推广草稿。", true, "预算300万，上海浦东，两居，小红书，选实际对比中总价较低的一套。"),
                new EvaluationCase("explicit-conflict", "虽然指定了找房技能，但本次仅问候，请回答你好，不查询房源，也不生成草稿。", true, null));
        for (EvaluationCase sample : cases) {
            var turns = new ArrayList<Map<String, Object>>();
            ChatModel observed = prompt -> {
                long started = System.nanoTime(); var response = actual.call(prompt);
                var output = response.getResult().getOutput(); var usage = response.getMetadata().getUsage();
                var turn = new LinkedHashMap<String, Object>();
                turn.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
                turn.put("promptTokens", usage.getPromptTokens()); turn.put("completionTokens", usage.getCompletionTokens());
                turn.put("text", output.getText()); turn.put("toolCalls", output.getToolCalls());
                turn.put("modelReported", response.getMetadata().getModel()); turns.add(turn);
                return response;
            };
            var effects = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
            var catalog = fixtureCatalog(effects, mapper);
            var props = new SupervisorOrchestrationProperties(); props.setModelRetries(0);
            var store = OrchestrationStoreTest.database(mapper); var saver = new MemorySaver();
            var factory = new SupervisorToolLoopGraph(catalog, new SupervisorModelTurn(observed), skills, store, mapper, props,
                    mock(SessionStreamService.class), new OfficialSupervisorGraphSchema(), mock(DatabaseCheckpointSaverFactory.class));
            var graph = factory.create(saver);
            var runner = new SupervisorToolLoopRunner(factory, graph, store, props, mock(ApprovalResumeClaimStore.class));
            String run = "eval-" + sample.id() + "-" + UUID.randomUUID();
            long started = System.nanoTime();
            try {
                var request = new SupervisorTaskRequest("eval-session", "1", run, run, sample.text(), Map.of(), "api", false,
                        sample.explicit() ? new SkillSelection("listing-compare-marketing-draft", "1") : null, null, true);
                var response = runner.execute(request);
                String initialStatus = response.status(); String initialAnswer = response.finalAnswer();
                var initialEffects = List.copyOf(effects);
                if ("WAITING_USER_INPUT".equals(response.status()) && sample.continuation() != null)
                    response = runner.execute(new SupervisorTaskRequest("eval-session", "1", run + "-input", run,
                            sample.continuation(), Map.of(), "api", false, null, run, null));
                var report = new LinkedHashMap<String, Object>();
                report.put("case", sample.id()); report.put("request", sample.text()); report.put("runId", run);
                report.put("initialStatus", initialStatus); report.put("initialAnswer", initialAnswer); report.put("initialEffects", initialEffects);
                report.put("continuation", sample.continuation()); report.put("status", response.status()); report.put("finalAnswer", response.finalAnswer());
                report.put("metadata", response.governanceMetadata()); report.put("effects", List.copyOf(effects));
                report.put("turns", turns); report.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
                reports.add(report);
                assertThat(turns).isNotEmpty(); // Infrastructure assertion; semantic deviations remain in the report.
                System.out.println("Real model case=" + sample.id() + ", status=" + response.status() + ", turns=" + turns.size() + ", effects=" + effects.size());
            } finally { runner.close(); factory.close(); }
        }
        var report = Map.of("modelRequested", modelName, "temperature", 0, "runnerVersion", SupervisorToolLoopGraph.RUNNER_VERSION,
                "modelTimeoutMs", 30_000, "maxRounds", 12, "maxToolCalls", 24, "businessData", "deterministic fixtures; real model decisions", "cases", reports);
        Path target = Path.of("target", "supervisor-real-model-evaluation.json"); Files.createDirectories(target.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
    }

    private SupervisorCapabilityCatalog fixtureCatalog(List<Map<String, Object>> effects, ObjectMapper mapper) {
        var registry = mock(AgentRegistry.class); var permissions = mock(AgentPermissionService.class);
        when(permissions.canInvokeChildAgent(eq("1"), any())).thenReturn(true);
        when(permissions.canUseMcpTools("1")).thenReturn(true); when(permissions.canReadMcpServer(eq("1"), any())).thenReturn(true);
        var descriptions = Map.of("listing", "根据地区、预算、户型查询真实房源，返回候选 ID 与详情。",
                "marketing", "基于真实房源和对比形成营销草稿，发布的 marketing-draft 仅草稿不发布。",
                "contract", "审阅已提供的合同原文，分析条款风险与违约责任，不发送通知。",
                "notification", "向联系人发送通知，会产生发送副作用，仅用户明确要求时调用。");
        var skillNames = Map.of("listing", "listing-search", "marketing", "marketing-draft", "contract", "contract-risk-review", "notification", "notification-send");
        var descriptors = new ArrayList<RegisteredAgentDescriptor>();
        for (var domain : List.of("listing", "marketing", "contract", "notification")) {
            String agentId = domain + "-agent", name = skillNames.get(domain), skillId = "bk-skill/" + domain + "/" + name;
            var publication = new SkillPublication(skillId, name, domain, "1", "evaluation-fixture-hash");
            var card = new AgentCard(agentId, agentId, descriptions.get(domain), "1", List.of(name), List.of(domain), false, false,
                    "http://evaluation-fixture/a2a", List.of("text"), List.of("application/json"),
                    List.of(new AgentSkillDescriptor(skillId, name, descriptions.get(domain), List.of(domain), List.of(), List.of(), List.of())),
                    Map.of("extensions", List.of(Map.of("uri", SkillPublication.EXTENSION_URI, "params",
                            Map.of("contractVersion", "1", "owner", domain, "skills", List.of(publication))))), "JSONRPC", "0.2.5");
            var descriptor = new RegisteredAgentDescriptor(agentId, "http://evaluation-fixture", "/.well-known/agent.json", "/a2a",
                    AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD, card, Map.of());
            descriptors.add(descriptor); when(registry.getByAgentId(agentId)).thenReturn(Optional.of(descriptor));
        }
        when(registry.listDescriptors()).thenReturn(descriptors);
        var results = Map.of(
                "listing-agent", "{\"listingIds\":[101,102],\"listings\":[{\"id\":101,\"district\":\"浦东\",\"rooms\":2,\"priceWan\":280},{\"id\":102,\"district\":\"浦东\",\"rooms\":2,\"priceWan\":295}],\"source\":\"evaluation-fixture\"}",
                "marketing-agent", "{\"contentType\":\"copy_draft\",\"platform\":\"小红书\",\"listingId\":101,\"draftText\":\"浦东两居，总价280万，适合首次置业家庭。具体信息以核验为准。\",\"published\":false,\"source\":\"evaluation-fixture\"}",
                "contract-agent", "{\"analysis\":\"买方与卖方逾期责任明显不对等，建议明确对等的违约责任及交付期限\",\"source\":\"evaluation-fixture\"}",
                "notification-agent", "{\"sent\":true}");
        var execution = mock(A2aExecutionService.class);
        when(execution.execute(any(), any(), eq("tool"), anyMap(), any())).thenAnswer(invocation -> {
            AgentTaskInvokeRequest request = invocation.getArgument(1);
            var arguments = new LinkedHashMap<String, Object>(); arguments.put("instruction", request.instruction());
            arguments.put("context", request.structuredContext()); arguments.put("skillSelection", request.skillSelection());
            effects.add(Map.of("capabilityId", "a2a:" + request.targetAgentId(), "arguments", arguments));
            return new AgentTaskInvokeResponse(request.sessionId(), request.taskId(), request.targetAgentId(), "COMPLETED",
                    mapper.readValue(results.get(request.targetAgentId()), Map.class), List.of(), List.of(), "fixture result", request.traceId());
        });
        var mcp = mock(AgentMcpClient.class);
        when(mcp.listTools()).thenReturn(List.of(new AgentMcpToolDescriptor("compare-mcp-server", "compareListings", "对比实际候选 ID 对应的价格与户型。",
                "{\"type\":\"object\",\"properties\":{\"listingIds\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"listingIds\"],\"additionalProperties\":false}", null)));
        when(mcp.callTool(eq("compare-mcp-server"), eq("compareListings"), anyMap())).thenAnswer(invocation -> {
            effects.add(Map.of("capabilityId", "mcp:compare-mcp-server:compareListings", "arguments", invocation.getArgument(2)));
            return new AgentMcpCallResult("compare-mcp-server", "compareListings", "101总价280万，102总价295万，均两居，101总价较低。",
                    Map.of("listingIds", List.of(101,102), "lowerPriceListingId", 101, "source", "evaluation-fixture"));
        });
        return new SupervisorCapabilityCatalog(registry, mcp, permissions, execution, org.springframework.ai.tool.ToolCallbackProvider.from(), mapper);
    }
    private record EvaluationCase(String id, String text, boolean explicit, String continuation) {}
}
