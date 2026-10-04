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

    @Test
    void changedInputIdentityAndOwnerAndPolicyAreRejected() {
        when(model.call(anyList(), anyList())).thenReturn(calls(call("question", "request_input", "{\"question\":\"地区\"}")), new AssistantMessage("done"));
        runner.execute(request("waiting-policy", null, null, "find"));
        assertThatThrownBy(() -> runner.execute(new SupervisorTaskRequest("session", "2", "input", "trace", "浦东", Map.of(), "api", false, null, "waiting-policy", null)))
                .hasMessage("CONTINUATION_OWNER_MISMATCH");
        assertThatThrownBy(() -> runner.execute(new SupervisorTaskRequest("session", "1", "input", "trace", "浦东", Map.of(), "api", false, null, "waiting-policy", false)))
                .hasMessage("CONTINUATION_POLICY_CHANGED");
        runner.execute(request("input", null, "waiting-policy", "浦东"));
        assertThatThrownBy(() -> runner.execute(request("input", null, "waiting-policy", "徐汇"))).hasMessage("TOOL_CALL_ID_CONFLICT");
        assertThatThrownBy(() -> runner.execute(request("another", null, "waiting-policy", "浦东"))).hasMessage("CONTINUATION_STATE_INVALID");
    }

    @Test
    void explicitBodyOrderDoesNotAddBusinessPrerequisiteNodes() {
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:first", capability("first"), "local:second", capability("second")));
        when(skills.getByName("ordered")).thenReturn(SkillDefinition.builder().name("ordered").description("steps").domain("supervisor")
                .tools(List.of("first", "second")).systemPrompt("先 first，再 second").build());
        when(model.call(anyList(), anyList())).thenReturn(calls(call("two", "second", "{}")), calls(call("one", "first", "{}")), new AssistantMessage("done"));
        assertThat(runner.execute(request("reorder", new SkillSelection("ordered", "1"), null, "find")).status()).isEqualTo("COMPLETED");
        assertThat(effects).hasValue(2);
        assertThat(store.invocations("reorder")).extracting(OrchestrationStore.Invocation::capabilityId).containsExactly("local:second", "local:first");
    }

    @Test
    void originalCallResultsSurviveEventFailureAndCanReconcileWithoutResend() {
        SessionStreamService broken = mock(SessionStreamService.class);
        doThrow(new IllegalStateException("events down")).when(broken).publish(any());
        factory.close();
        factory = new SupervisorToolLoopGraph(catalog, model, skills, store, mapper, properties, broken,
                new OfficialSupervisorGraphSchema(), mock(DatabaseCheckpointSaverFactory.class));
        try { graph = factory.create(new MemorySaver()); } catch (Exception exception) { throw new IllegalStateException(exception); }
        runner.close(); runner = new SupervisorToolLoopRunner(factory, graph, store, properties, claims);
        when(model.call(anyList(), anyList())).thenReturn(calls(call("one", "search", "{}")), new AssistantMessage("done"));
        assertThat(runner.execute(request("events", null, null, "find")).status()).isEqualTo("COMPLETED");
        assertThat(store.find("events", "one").status()).isEqualTo("COMPLETED");
        assertThat(effects).hasValue(1);
    }

    @Test
    void cancellationWhileWaitingCannotBeBypassedByContinuation() {
        when(model.call(anyList(), anyList())).thenReturn(calls(call("question", "request_input", "{\"question\":\"地区\"}")));
        runner.execute(request("cancel", null, null, "find"));
        assertThat(runner.cancel("cancel", "1").status()).isEqualTo("CANCELED");
        assertThatThrownBy(() -> runner.execute(request("input", null, "cancel", "浦东"))).hasMessage("CONTINUATION_STATE_INVALID");
        assertThat(effects).hasValue(0);
    }

    @Test
    void confirmedLateResultReturnsToModelWithoutExecutingToolAgain() {
        ToolCallback failed = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("search").description("search").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { effects.incrementAndGet(); throw new IllegalStateException("network"); }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:search", new SupervisorCapability("local:search", "local", "search", "1", failed)));
        when(model.call(anyList(), anyList())).thenReturn(calls(call("unknown", "search", "{}")), new AssistantMessage("confirmed"));
        runner.execute(request("late", null, null, "find"));
        store.complete("late", "unknown", "confirmed actual result");
        assertThat(runner.reconcile("late", "1").status()).isEqualTo("COMPLETED");
        assertThat(effects).hasValue(1);
        assertThat(state("late").messages.stream().filter(message -> "tool".equals(message.role())).findFirst().orElseThrow().responses().get(0).responseData())
                .isEqualTo("confirmed actual result");
    }

    @Test
    void separateChangedParametersRequireNewApprovalAndRepeatedCallbackCannotExecuteAgain() {
        when(model.call(anyList(), anyList())).thenReturn(calls(call("original", "search", "{\"limit\":1}")),
                calls(call("changed", "search", "{\"limit\":2}")), new AssistantMessage("done"));
        runner.execute(new SupervisorTaskRequest("session", "1", "twice", "trace", "find", Map.of("requireApproval", true), "api", false));
        var first = state("twice").pendingApproval;
        var callback = new com.bkanent.common.agent.ApprovalCallbackRequest(first.approvalId(), "twice", "session", com.bkanent.common.agent.ApprovalStatus.APPROVED, "1", null, "trace", first.subjectVersion());
        assertThat(runner.resume(callback).status()).isEqualTo("WAITING_USER_APPROVAL");
        assertThat(effects).hasValue(1);
        var second = state("twice").pendingApproval;
        assertThat(second.subjectId()).isNotEqualTo(first.subjectId());
        // Simulate completed claim replay while another actual-call approval is pending.
        when(claims.claim(callback)).thenReturn(ApprovalResumeClaimStore.ClaimResult.replay(factory.responseOf(state("twice"))));
        runner.resume(callback);
        assertThat(effects).hasValue(1);
        runner.resume(new com.bkanent.common.agent.ApprovalCallbackRequest(second.approvalId(), "twice", "session", com.bkanent.common.agent.ApprovalStatus.APPROVED, "1", null, "trace", second.subjectVersion()));
        assertThat(effects).hasValue(2);
    }

    @Test
    void independentCallsRunInParallelAndBudgetStopsUnboundedCalls() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        ToolCallback parallel = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("search").description("search").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) {
                entered.countDown();
                try { if (!release.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("not parallel"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                effects.incrementAndGet(); return "actual";
            }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:search", new SupervisorCapability("local:search", "local", "search", "1", parallel)));
        when(model.call(anyList(), anyList())).thenReturn(calls(call("a", "search", "{}"), call("b", "search", "{}")), new AssistantMessage("done"));
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> runner.execute(request("parallel", null, null, "find")));
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); release.countDown();
            assertThat(result.get(3, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
        } finally { release.countDown(); executor.shutdownNow(); }
        assertThat(effects).hasValue(2);
        properties.setMaxToolCalls(1);
        when(model.call(anyList(), anyList())).thenReturn(calls(call("c", "search", "{}"), call("d", "search", "{}")));
        assertThat(runner.execute(request("budget", null, null, "find")).governanceMetadata()).containsEntry("errorCode", "BUDGET_EXHAUSTED");
        assertThat(effects).hasValue(2);
    }

    @Test
    void modelDeadlineAndPauseNewAcceptancePreserveSameModeRecovery() {
        properties.setModelTimeoutMs(20); properties.setModelRetries(0);
        when(model.call(anyList(), anyList())).thenAnswer(invocation -> { Thread.sleep(5000); return new AssistantMessage("late"); });
        assertThat(runner.execute(request("timeout", null, null, "find")).governanceMetadata()).containsEntry("errorCode", "MODEL_UNAVAILABLE");
        properties.setAccepting(false);
        assertThatThrownBy(() -> runner.execute(request("new", null, null, "find"))).hasMessage("SUPERVISOR_ACCEPTANCE_PAUSED");
        assertThat(runner.execute(request("timeout", null, null, "find")).status()).isEqualTo("FAILED");
        assertThat(effects).hasValue(0);
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
