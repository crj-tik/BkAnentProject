package com.bkanent.agent.orchestration;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.bkanent.agent.graph.official.*;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.SkillSelection;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupervisorToolLoopGraphTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SupervisorCapabilityCatalog catalog = mock(SupervisorCapabilityCatalog.class);
    private final SupervisorModelTurn model = mock(SupervisorModelTurn.class);
    private final SkillRegistry skills = mock(SkillRegistry.class);
    private final SupervisorOrchestrationProperties properties = new SupervisorOrchestrationProperties();
    private final AtomicInteger effects = new AtomicInteger();
    private OrchestrationStore store;
    private SupervisorToolLoopGraph factory;
    private SupervisorToolLoopRunner runner;
    private CompiledGraph graph;
    private final ApprovalResumeClaimStore claims = mock(ApprovalResumeClaimStore.class);

    @BeforeEach
    void setup() throws Exception {
        store = OrchestrationStoreTest.database(mapper);
        factory = new SupervisorToolLoopGraph(catalog, model, skills, store, mapper, properties,
                mock(SessionStreamService.class), new OfficialSupervisorGraphSchema(), mock(DatabaseCheckpointSaverFactory.class));
        graph = factory.create(new MemorySaver());
        runner = new SupervisorToolLoopRunner(factory, graph, store, properties, claims);
        when(claims.claim(any())).thenReturn(ApprovalResumeClaimStore.ClaimResult.claimed());
        when(skills.findOperationalSkills("supervisor")).thenReturn(List.of());
        when(skills.findSupervisorSkills()).thenReturn(List.of());
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:search", capability("search")));
    }
    @AfterEach void cleanup() { factory.close(); runner.close(); }

    @Test
    void modelChoosesActualToolAndNextHintsNeverTriggerImplicitHandoff() {
        when(model.call(anyList(), anyList())).thenReturn(calls(call("one", "search", "{}")), new AssistantMessage("done"));
        var response = runner.execute(request("run", null, null, "find"));
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(effects).hasValue(1);
        assertThat(runner.execute(request("run", null, null, "find"))).isEqualTo(response);
        assertThat(effects).hasValue(1);
        assertThat(store.find("run", "one").result()).contains("nextHints");
    }

    @Test
    void mixedControlBatchRejectsEveryCallWithNoActivationWaitingApprovalOrBusinessSideEffect() {
        when(model.call(anyList(), anyList())).thenReturn(calls(
                call("skill-call", "skill", "{\"name\":\"find\",\"task\":\"find\"}"),
                call("business-call", "search", "{}"),
                call("input-call", "request_input", "{\"question\":\"预算?\"}")), new AssistantMessage("done"));
        runner.execute(request("mixed", null, null, "find"));
        assertThat(effects).hasValue(0);
        var state = state("mixed");
        assertThat(state.skillSnapshot).isNull();
        assertThat(state.pendingApproval).isNull();
        assertThat(state.question).isNull();
        assertThat(state.messages.stream().filter(message -> "tool".equals(message.role())).findFirst().orElseThrow().responses())
                .extracting(ToolResponseMessage.ToolResponse::id).containsExactly("skill-call", "business-call", "input-call");
        verify(skills, never()).getByName(anyString());
    }

    @Test
    void explicitSkillIsLoadedBeforeModelAndModelCanFinishWithoutBusinessStepChecks() {
        var definition = SkillDefinition.builder().name("find").description("find").domain("supervisor")
                .tools(List.of("search")).systemPrompt("先理解预算，再搜索，再汇总").build();
        when(skills.getByName("find")).thenReturn(definition);
        when(model.call(anyList(), anyList())).thenAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(0);
            assertThat(messages.get(0).getText()).contains("预算300万", "先理解预算，再搜索，再汇总");
            return new AssistantMessage("需要先确认地区");
        });
        var response = runner.execute(request("explicit", new SkillSelection("find", "1"), null, "预算300万"));
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.governanceMetadata()).containsEntry("mode", "EXPLICIT_SKILL");
        assertThat(effects).hasValue(0);
    }

    @Test
    void waitingInputResumesSameSnapshotAndCumulativeBudgetAndDeduplicatesInput() {
        when(skills.getByName("find")).thenReturn(SkillDefinition.builder().name("find").description("find").domain("supervisor")
                .tools(List.of("search")).systemPrompt("old body").build());
        when(model.call(anyList(), anyList())).thenReturn(calls(call("question", "request_input", "{\"question\":\"在哪个地区\"}")), new AssistantMessage("done"));
        var waiting = runner.execute(request("waiting", new SkillSelection("find", "1"), null, "find"));
        assertThat(waiting.status()).isEqualTo("WAITING_USER_INPUT");
        assertThat(effects).hasValue(0);
        when(skills.getByName("find")).thenReturn(SkillDefinition.builder().name("find").description("find").domain("supervisor")
                .version("2").tools(List.of("search")).systemPrompt("new body").build());
        var input = request("input-1", null, "waiting", "浦东");
        var completed = runner.execute(input);
        assertThat(completed.taskId()).isEqualTo("waiting");
        assertThat(state("waiting").skillSnapshot.definition().systemPrompt()).isEqualTo("old body");
        assertThat(state("waiting").rounds).isEqualTo(2);
        assertThat(runner.execute(input)).isEqualTo(completed);
        verify(model, times(2)).call(anyList(), anyList());
    }

    @Test
    void unknownExecutionStopsFurtherModelTurnsAndNeverResends() {
        ToolCallback failing = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("search").description("effect")
                    .inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { effects.incrementAndGet(); throw new IllegalStateException("network lost after send"); }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:search", new SupervisorCapability("local:search", "local", "search", "1", failing)));
        when(model.call(anyList(), anyList())).thenReturn(calls(call("uncertain", "search", "{}")));
        var response = runner.execute(request("unknown", null, null, "find"));
        assertThat(response.governanceMetadata()).containsEntry("errorCode", "OUTCOME_UNKNOWN");
        assertThat(store.find("unknown", "uncertain").status()).isEqualTo("OUTCOME_UNKNOWN");
        runner.execute(request("unknown", null, null, "find"));
        assertThat(effects).hasValue(1);
        verify(model, times(1)).call(anyList(), anyList());
    }

    @Test
    void actualCallApprovalResumesOriginalParametersAndRejectionCannotReachModelAgain() {
        var request = new SupervisorTaskRequest("session", "1", "approval", "trace", "find",
                Map.of("requireApproval", true), "api", false);
        when(model.call(anyList(), anyList())).thenReturn(calls(call("approved-call", "search", "{}")), new AssistantMessage("done"));
        assertThat(runner.execute(request).status()).isEqualTo("WAITING_USER_APPROVAL");
        assertThat(effects).hasValue(0);
        var pending = state("approval").pendingApproval;
        assertThat(pending.payload().get("calls").toString()).contains("approved-call", "local:search", "argumentsHash");
        var approved = runner.resume(new com.bkanent.common.agent.ApprovalCallbackRequest(pending.approvalId(), "approval", "session",
                com.bkanent.common.agent.ApprovalStatus.APPROVED, "1", null, "trace", pending.subjectVersion()));
        assertThat(approved.status()).isEqualTo("COMPLETED");
        assertThat(effects).hasValue(1);

        when(model.call(anyList(), anyList())).thenReturn(calls(call("denied-call", "search", "{}")));
        runner.execute(new SupervisorTaskRequest("session", "1", "denial", "trace", "find", Map.of("requireApproval", true), "api", false));
        var deniedPending = state("denial").pendingApproval;
        var denied = runner.resume(new com.bkanent.common.agent.ApprovalCallbackRequest(deniedPending.approvalId(), "denial", "session",
                com.bkanent.common.agent.ApprovalStatus.REJECTED, "1", null, "trace", deniedPending.subjectVersion()));
        assertThat(denied.status()).isEqualTo("CANCELED");
        assertThat(effects).hasValue(1);
        assertThat(runner.execute(request("denial", null, null, "find")).status()).isEqualTo("CANCELED");
    }

    @Test
    void failedAndExplicitOnlyGuessedSkillLoadsDoNotActivate() {
        when(skills.getByName("private")).thenReturn(SkillDefinition.builder().name("private").description("private")
                .domain("supervisor").tools(List.of("search")).explicitOnly(true).systemPrompt("private body").build());
        when(model.call(anyList(), anyList())).thenReturn(calls(call("load", "skill", "{\"name\":\"private\",\"task\":\"find\"}")), new AssistantMessage("done"));
        runner.execute(request("private", null, null, "find"));
        assertThat(state("private").skillSnapshot).isNull();
        assertThat(store.find("private", "load").status()).isEqualTo("COMPLETED");
        assertThat(store.find("private", "load").result()).contains("SKILL_EXPLICIT_REQUIRED");
    }

    @Test
    void newRequestInSameSessionDoesNotInheritExplicitAndDisabledMcpConflictFailsBeforeModel() {
        when(skills.getByName("find")).thenReturn(SkillDefinition.builder().name("find").description("find")
                .domain("supervisor").tools(List.of("search")).systemPrompt("selected body").build());
        when(model.call(anyList(), anyList())).thenReturn(new AssistantMessage("done"));
        runner.execute(request("selected", new SkillSelection("find", "1"), null, "find"));
        runner.execute(request("new-auto", null, null, "find"));
        assertThat(state("new-auto").skillSnapshot).isNull();
        assertThat(state("new-auto").mode).isEqualTo("AUTO");
        when(skills.getByName("mcp-required")).thenReturn(SkillDefinition.builder().name("mcp-required").description("mcp")
                .domain("supervisor").capabilities(new com.bkanent.common.skill.SkillCapabilityPolicy("allowlist", List.of("mcp:server:tool"))).build());
        when(catalog.snapshot("1", false)).thenReturn(Map.of());
        assertThat(runner.execute(new SupervisorTaskRequest("session", "1", "mcp-disabled", "trace", "find", Map.of(), "api", false,
                new SkillSelection("mcp-required", "1"), null, false)).status()).isEqualTo("FAILED");
        verify(model, times(2)).call(anyList(), anyList());
    }

    @Test
    void invalidQuestionDoesNotSuspendAndModelFailureUsesBoundedSameModeRetry() {
        when(model.call(anyList(), anyList())).thenReturn(calls(call("invalid-question", "request_input", "{}")), new AssistantMessage("done"));
        assertThat(runner.execute(request("bad-input", null, null, "find")).status()).isEqualTo("COMPLETED");
        when(model.call(anyList(), anyList())).thenThrow(new IllegalStateException("model down"));
        assertThat(runner.execute(request("model-down", null, null, "find")).governanceMetadata()).containsEntry("errorCode", "MODEL_UNAVAILABLE");
        assertThat(effects).hasValue(0);
        assertThat(state("model-down").rounds).isEqualTo(3);
    }

    @Test
    void catalogIsLightweightUntilSuccessfulSkillLoadAndMultipleControlCallsAreRejected() {
        var ordinary = SkillDefinition.builder().name("find").description("ordinary summary").domain("supervisor")
                .tools(List.of("search")).systemPrompt("full skill body").build();
        var privateSkill = SkillDefinition.builder().name("private").description("private summary").domain("supervisor")
                .tools(List.of("search")).systemPrompt("private body").explicitOnly(true).build();
        when(skills.findOperationalSkills("supervisor")).thenReturn(List.of(ordinary, privateSkill));
        when(skills.getByName("find")).thenReturn(ordinary);
        AtomicInteger turns = new AtomicInteger();
        when(model.call(anyList(), anyList())).thenAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(0);
            String prompt = messages.get(0).getText();
            int turn = turns.getAndIncrement();
            if (turn == 0) {
                assertThat(prompt).contains("ordinary summary").doesNotContain("full skill body", "private summary", "private body");
                return calls(call("load-one", "skill", "{\"name\":\"find\",\"task\":\"find\"}"),
                        call("load-two", "skill", "{\"name\":\"find\",\"task\":\"find\"}"));
            }
            if (turn == 1) {
                assertThat(prompt).doesNotContain("full skill body");
                return calls(call("load-only", "skill", "{\"name\":\"find\",\"task\":\"find\"}"));
            }
            assertThat(prompt).contains("full skill body", "原始任务");
            return new AssistantMessage("done");
        });
        runner.execute(request("catalog", null, null, "原始任务"));
        assertThat(state("catalog").skillSnapshot.definition()).isEqualTo(ordinary);
        assertThat(effects).hasValue(0);
        verify(skills, times(1)).getByName("find");
    }

    private ToolLoopState state(String id) { return factory.stateOf(graph.lastStateOf(com.alibaba.cloud.ai.graph.RunnableConfig.builder().threadId(id).build()).orElseThrow().state()); }
    private SupervisorTaskRequest request(String id, SkillSelection skill, String continueId, String message) {
        return new SupervisorTaskRequest("session", "1", id, "trace", message, Map.of(), "api", false, skill, continueId, null);
    }
    private SupervisorCapability capability(String name) {
        ToolCallback callback = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(name).description("actual " + name).inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { effects.incrementAndGet(); return "{\"nextHints\":[\"contract\"],\"result\":\"real\"}"; }
        };
        return new SupervisorCapability("local:" + name, "local", name, "1", callback);
    }
    private AssistantMessage.ToolCall call(String id, String name, String arguments) { return new AssistantMessage.ToolCall(id, "function", name, arguments); }
    private AssistantMessage calls(AssistantMessage.ToolCall... calls) { return AssistantMessage.builder().content("").toolCalls(List.of(calls)).build(); }
}
