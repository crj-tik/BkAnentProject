package com.bkanent.agent.orchestration;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.bkanent.agent.graph.official.*;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.authorization.AgentAuthorizationConsumer;
import com.bkanent.agent.authorization.AgentAuthorizationScope;
import com.bkanent.agent.config.AgentAuthorizationProperties;
import com.bkanent.agent.config.AgentRagConsumerProperties;
import com.bkanent.agent.security.AgentRagCredentialLeaseStore;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.SkillSelection;
import com.bkanent.common.authz.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private com.bkanent.agent.memory.SupervisorMemoryLifecycle memory;
    private OrchestrationStore store;
    private SupervisorToolLoopGraph factory;
    private SupervisorToolLoopRunner runner;
    private CompiledGraph graph;
    private final ApprovalResumeClaimStore claims = mock(ApprovalResumeClaimStore.class);
    private final SessionStreamService events = mock(SessionStreamService.class);

    @BeforeEach
    void setup() throws Exception {
        store = OrchestrationStoreTest.database(mapper);
        memory = successfulMemory();
        factory = newFactory(events);
        graph = factory.create(new MemorySaver());
        runner = new SupervisorToolLoopRunner(factory, graph, store, properties, claims);
        when(claims.claim(any())).thenReturn(ApprovalResumeClaimStore.ClaimResult.claimed());
        when(skills.findOperationalSkills("supervisor")).thenReturn(List.of());
        when(skills.findSupervisorSkills()).thenReturn(List.of());
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:search", capability("search")));
    }
    @AfterEach void cleanup() { factory.close(); runner.close(); }

    private com.bkanent.agent.memory.SupervisorMemoryLifecycle successfulMemory() {
        var lifecycle = mock(com.bkanent.agent.memory.SupervisorMemoryLifecycle.class);
        doAnswer(invocation -> {
            ToolLoopState state = invocation.getArgument(0);
            state.memoryMode = "V2";
            state.memoryDeliveryAccepted = true;
            state.memoryStatus = "LOADED";
            return null;
        }).when(lifecycle).prepare(any());
        when(lifecycle.applies(any())).thenReturn(true);
        when(lifecycle.beforeModel(any(), anyString())).thenAnswer(invocation -> {
            ToolLoopState state = invocation.getArgument(0);
            return state.messages.stream().map(StoredModelMessage::message).toList();
        });
        return lifecycle;
    }

    private SupervisorToolLoopGraph newFactory(SessionStreamService stream) {
        var result = new SupervisorToolLoopGraph(catalog, model, skills, store, mapper, properties,
                stream, new OfficialSupervisorGraphSchema(), mock(DatabaseCheckpointSaverFactory.class));
        result.setMemory(memory);
        return result;
    }

    @Test
    void invalidChildSkillIsRejectedBeforeApprovalAndClaimThenModelCanCorrectIt() {
        doThrow(new IllegalArgumentException("SKILL_NOT_FOUND")).when(catalog).validateArguments(any(), eq(Map.of("skill", Map.of("name", "invalid"))));
        when(model.call(anyList(), anyList())).thenReturn(calls(call("invalid", "search", "{\"skill\":{\"name\":\"invalid\"}}")),
                calls(call("corrected", "search", "{}")), new AssistantMessage("done"));
        var response = runner.execute(request("child-guard", null, null, "find"));
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(store.find("child-guard", "invalid")).isNull();
        assertThat(store.find("child-guard", "corrected").status()).isEqualTo("COMPLETED");
        assertThat(effects).hasValue(1);
    }

    @Test
    void graphReplansCheckOneAuthorizationHeadAndToolBatchAddsNoPerToolAuthOrRedisReads() throws Exception {
        long now = 1_800_000_000L;
        String actor = "1";
        String token = "verified-test-token";
        var clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC);
        var generator = KeyPairGenerator.getInstance("Ed25519");
        var authority = generator.generateKeyPair();
        var caller = generator.generateKeyPair();
        var authorizationProperties = new AgentAuthorizationProperties();
        authorizationProperties.setEnabled(true);
        authorizationProperties.setKeyId("agent-key");
        authorizationProperties.setPublicKey(Base64.getEncoder().encodeToString(caller.getPublic().getEncoded()));
        authorizationProperties.setAuthorityPublicKeys(Map.of("auth-key",
                Base64.getEncoder().encodeToString(authority.getPublic().getEncoded())));
        var authorizationRpc = mock(AuthAuthorizationRpcService.class);
        var legacyRpc = mock(AuthPermissionRpcService.class);
        AtomicInteger headReads = new AtomicInteger();

        byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String nonce = authzNonce((byte) 1);
        String policyDigest = AuthzCanonicalHash.bodyHash(new byte[0]);
        var binding = new RequestBinding("gateway", "agent-service", "POST", "/agent/chat", "/agent/chat",
                List.of(), "run-19", "run-19", "session-19", AuthzCanonicalHash.bodyHash(body),
                policyDigest, nonce, now - 1, now + 59);
        var snapshot = AuthzSignature.sign(new AuthorizationSnapshot(actor, 4, 1, false,
                List.of("agent.chat.use"), null, AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
        String requestHash = AuthzCanonicalHash.resolveRequestHash(token, binding);
        var freshness = AuthzSignature.sign(new AuthzFreshness(actor, 4, 2, AuthorizationStatus.ACTIVE,
                permissionHash, now - 1, now + 29, authzNonce((byte) 2), AuthzContract.ALGORITHM,
                "auth-key", null), authority.getPrivate());
        var context = AuthzSignature.sign(new AuthorizationContext(actor, 4, 2, permissionHash,
                snapshot.permissions(), "agent-service", binding.requestId(), binding.runId(), binding.sessionId(),
                policyDigest, requestHash, nonce, now - 1, now + 120, now + 120,
                AuthzContract.ALGORITHM, "auth-key", null), authority.getPrivate());
        var monitor = new AuthorizationVersionMonitor(users -> {
            headReads.incrementAndGet();
            return Map.of(actor, freshness);
        }, authorizationProperties.authorityKeys(), clock);
        var consumer = new AgentAuthorizationConsumer(authorizationProperties,
                new AuthorizationCache(16, 1_000_000, java.time.Duration.ofMinutes(1), 8), monitor, clock,
                authorizationRpc);
        var entry = consumer.verify(new AuthzResponse(snapshot, context, freshness), binding, token,
                "POST", "/agent/chat", null, body);

        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("legacyAuth", legacyRpc);
        var credentials = new AgentRagCredentialLeaseStore(new AgentRagConsumerProperties(),
                beans.getBeanProvider(AuthPermissionRpcService.class), clock);
        AtomicInteger memoryResourceChecks = new AtomicInteger();
        doAnswer(invocation -> { memoryResourceChecks.incrementAndGet(); return null; })
                .when(memory).authorizeStoredOutput(any());
        factory.setAuthorizationConsumer(consumer);
        factory.setAuthorizationProperties(authorizationProperties);
        factory.setRagCredentials(credentials);
        runner.setRagCredentials(credentials);
        when(model.call(anyList(), anyList())).thenReturn(calls(
                        call("tool-1", "search", "{}"), call("tool-2", "search", "{}"), call("tool-3", "search", "{}")),
                new AssistantMessage("done"));

        try (var authorization = AgentAuthorizationScope.open(entry);
             var verifiedCredentials = credentials.openVerifiedAuthorization(token, entry)) {
            var response = runner.execute(new SupervisorTaskRequest("session-19", actor, "run-19", "trace-19",
                    "find", Map.of(), "api", false));
            assertThat(response.status()).isEqualTo("COMPLETED");
        } finally {
            credentials.discard("run-19");
        }

        assertThat(effects).hasValue(3);
        assertThat(headReads).hasValue(1);
        verify(model, times(2)).call(anyList(), anyList());
        verify(authorizationRpc, never()).refreshAuthorization(anyString(), any(), any(), any());
        verifyNoInteractions(legacyRpc);
        verify(memory, times(2)).beforeModel(any(), anyString());
        assertThat(memoryResourceChecks.get()).isEqualTo(4);
        var streamEvents = org.mockito.ArgumentCaptor.forClass(com.bkanent.common.agent.SessionStreamEvent.class);
        verify(events, times(4)).publish(streamEvents.capture());
        assertThat(streamEvents.getAllValues()).extracting(com.bkanent.common.agent.SessionStreamEvent::eventType)
                .containsExactlyInAnyOrder("tool.completed", "tool.completed", "tool.completed", "supervisor.completed");
        assertThat(headReads).hasValue(1);
    }

    @Test
    void waitingAndActualResultEventsCarryOwnerSkillIdentityAndRemainReplayable() {
        when(skills.getByName("find")).thenReturn(SkillDefinition.builder().name("find").description("find").domain("supervisor")
                .tools(List.of("search")).systemPrompt("body").build());
        when(model.call(anyList(), anyList())).thenReturn(calls(call("input", "request_input", "{\"question\":\"地区\"}")),
                calls(call("actual", "search", "{}")), new AssistantMessage("done"));
        runner.execute(request("stream", new SkillSelection("find", "1"), null, "find"));
        runner.execute(request("input-request", null, "stream", "浦东"));
        var capture = org.mockito.ArgumentCaptor.forClass(com.bkanent.common.agent.SessionStreamEvent.class);
        verify(events, atLeast(3)).publish(capture.capture());
        assertThat(capture.getAllValues()).anySatisfy(event -> {
            if (!"supervisor.waiting_user_input".equals(event.eventType())) throw new AssertionError("not waiting");
            assertThat(event.metadata()).containsEntry("userId", "1").containsEntry("mode", "EXPLICIT_SKILL").containsEntry("terminal", false);
            assertThat(event.metadata()).containsKeys("pendingInput", "skill", "runId");
        }).anySatisfy(event -> {
            if (!"tool.completed".equals(event.eventType())) throw new AssertionError("not tool result");
            assertThat(event.metadata()).containsEntry("callId", "actual").containsEntry("capabilityId", "local:search")
                    .containsEntry("terminal", false).containsEntry("userId", "1");
        });
    }

    @Test
    void confirmedFailedRemoteTaskIsAResultWithoutAFabricatedArtifact() {
        var artifacts = mock(com.bkanent.agent.graph.node.PersistArtifactsNode.class);
        factory.setArtifacts(artifacts);
        ToolCallback failedRemote = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("remote").description("remote").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { return "{\"status\":\"FAILED\",\"errorCode\":\"REMOTE_FAILURE\"}"; }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("a2a:listing-agent", new SupervisorCapability("a2a:listing-agent", "a2a", "remote", "1", failedRemote)));
        when(model.call(anyList(), anyList())).thenReturn(calls(call("failed", "remote", "{}")), new AssistantMessage("远端失败已确认"));
        assertThat(runner.execute(request("remote-failed", null, null, "find")).status()).isEqualTo("COMPLETED");
        verifyNoInteractions(artifacts);
        assertThat(store.find("remote-failed", "failed").result()).contains("REMOTE_FAILURE");
    }

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
            String combined = messages.stream().map(Message::getText).filter(Objects::nonNull).reduce("", (left, right) -> left + "\n" + right);
            assertThat(combined).contains("预算300万", "先理解预算，再搜索，再汇总");
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
        assertThat(runner.execute(new SupervisorTaskRequest("session", "1", "denial", "trace", "find",
                Map.of("requireApproval", true), "api", false)).status()).isEqualTo("CANCELED");
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
        factory = newFactory(broken);
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

    @Test
    void catalogFailuresAtPrepareModelGuardAndApprovalBecomeDurableFailures() {
        for (int failureAt = 0; failureAt < 4; failureAt++) {
            AtomicInteger snapshots = new AtomicInteger();
            int position = failureAt;
            when(catalog.snapshot("1", true)).thenAnswer(invocation -> {
                if (snapshots.getAndIncrement() == position) throw new IllegalStateException("duplicate catalog entry");
                return Map.of("local:search", capability("search"));
            });
            when(model.call(anyList(), anyList())).thenReturn(calls(call("catalog-call", "search", "{}")));
            var response = runner.execute(request("catalog-" + failureAt, null, null, "find"));
            assertThat(response.status()).isEqualTo("FAILED");
            assertThat(response.governanceMetadata()).containsEntry("errorCode", "CAPABILITY_CATALOG_UNAVAILABLE");
            assertThat(state("catalog-" + failureAt).status).isEqualTo("FAILED");
        }
        assertThat(effects).hasValue(0);
    }

    @Test
    void approvedTargetDisappearingOrChangingVersionFailsWithoutSending() {
        for (boolean removed : List.of(true, false)) {
            String id = "approved-" + removed;
            when(catalog.snapshot("1", true)).thenReturn(Map.of("local:search", capability("search")));
            when(model.call(anyList(), anyList())).thenReturn(calls(call("target", "search", "{}")));
            runner.execute(new SupervisorTaskRequest("session", "1", id, "trace", "find", Map.of("requireApproval", true), "api", false));
            var pending = state(id).pendingApproval;
            when(catalog.snapshot("1", true)).thenReturn(removed ? Map.of() : Map.of("local:search",
                    new SupervisorCapability("local:search", "local", "search", "2", capability("search").callback())));
            var response = runner.resume(new com.bkanent.common.agent.ApprovalCallbackRequest(pending.approvalId(), id, "session",
                    com.bkanent.common.agent.ApprovalStatus.APPROVED, "1", null, "trace", pending.subjectVersion()));
            assertThat(response.status()).isEqualTo("FAILED");
            assertThat(response.governanceMetadata()).containsEntry("errorCode", removed ? "CAPABILITY_UNAVAILABLE" : "CAPABILITY_VERSION_CHANGED");
            assertThat(store.find(id, "target").status()).isEqualTo("REJECTED");
            assertThat(state(id).pendingApproval).isNull();
        }
        assertThat(effects).hasValue(0);
    }

    @Test
    void approvalRejectionDoesNotDependOnLiveCatalog() {
        when(model.call(anyList(), anyList())).thenReturn(calls(call("denied", "search", "{}")));
        runner.execute(new SupervisorTaskRequest("session", "1", "offline-deny", "trace", "find", Map.of("requireApproval", true), "api", false));
        var pending = state("offline-deny").pendingApproval;
        when(catalog.snapshot("1", true)).thenThrow(new IllegalStateException("offline"));
        assertThat(runner.resume(new com.bkanent.common.agent.ApprovalCallbackRequest(pending.approvalId(), "offline-deny", "session",
                com.bkanent.common.agent.ApprovalStatus.REJECTED, "1", null, "trace", pending.subjectVersion())).status()).isEqualTo("CANCELED");
        assertThat(store.find("offline-deny", "denied").status()).isEqualTo("REJECTED");
        assertThat(effects).hasValue(0);
    }

    @Test
    void saturatedExecutorAuditsUnsentCallsAndSharedDeadlineBoundsWholeBatch() throws Exception {
        factory.close(); runner.close();
        properties.setMaxConcurrency(1); properties.setMaxQueuedCalls(1); properties.setToolTimeoutMs(200);
        factory = newFactory(events);
        graph = factory.create(new MemorySaver());
        runner = new SupervisorToolLoopRunner(factory, graph, store, properties, claims);
        var release = new java.util.concurrent.CountDownLatch(1);
        ToolCallback blocking = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return capability("search").callback().getToolDefinition(); }
            public String call(String input) {
                effects.incrementAndGet();
                // A submitted remote call cannot prove cancellation merely from a local interrupt.
                boolean done = false;
                while (!done) { try { done = release.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { } }
                return "actual";
            }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:search", new SupervisorCapability("local:search", "local", "search", "1", blocking)));
        var original = request("saturated", null, null, "find");
        store.register("saturated", original);
        store.prepare("saturated", "cached", "local:search", "{}");
        store.complete("saturated", "cached", "known result");
        when(model.call(anyList(), anyList())).thenReturn(calls(call("running", "search", "{}"), call("queued", "search", "{}"),
                call("rejected", "search", "{}"), call("cached", "search", "{}")));
        try {
            long start = System.nanoTime();
            var response = runner.execute(request("saturated", null, null, "find"));
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(550);
            assertThat(response.governanceMetadata()).containsEntry("errorCode", "OUTCOME_UNKNOWN");
            assertThat(store.find("saturated", "running").status()).isEqualTo("OUTCOME_UNKNOWN");
            assertThat(store.find("saturated", "queued").status()).isEqualTo("REJECTED");
            assertThat(store.find("saturated", "queued").result()).contains("CALL_DEADLINE_EXCEEDED");
            assertThat(store.find("saturated", "rejected").status()).isEqualTo("REJECTED");
            assertThat(store.find("saturated", "rejected").result()).contains("EXECUTOR_BUSY");
            assertThat(store.find("saturated", "cached").result()).isEqualTo("known result");
            assertThat(state("saturated").messages.stream().filter(message -> "tool".equals(message.role()))
                    .flatMap(message -> message.responses().stream()).filter(result -> "cached".equals(result.id())))
                    .singleElement().satisfies(result -> assertThat(result.responseData()).isEqualTo("known result"));
            var captured = org.mockito.ArgumentCaptor.forClass(com.bkanent.common.agent.SessionStreamEvent.class);
            verify(events, atLeastOnce()).publish(captured.capture());
            assertThat(captured.getAllValues()).anySatisfy(event -> {
                assertThat(event.eventType()).isEqualTo("tool.rejected");
                assertThat(event.metadata()).containsEntry("callId", "rejected");
            });
            assertThat(effects).hasValue(1);
        } finally { release.countDown(); }
    }

    @Test
    void definiteKnowledgeFailureCompletesAndFinishedLedgerDoesNotResend() {
        ToolCallback read = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("milvusKnowledgeSearch").description("read").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { effects.incrementAndGet(); throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.DEPENDENCY_UNAVAILABLE); }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:milvusKnowledgeSearch", new SupervisorCapability("local:milvusKnowledgeSearch", "local", "milvusKnowledgeSearch", "1", read)));
        when(model.call(anyList(), anyList())).thenReturn(calls(call("read", "milvusKnowledgeSearch", "{}")),
                calls(call("read", "milvusKnowledgeSearch", "{}")), new AssistantMessage("dependency unavailable"));
        assertThat(runner.execute(request("knowledge-failure", null, null, "find")).status()).isEqualTo("COMPLETED");
        assertThat(store.find("knowledge-failure", "read").status()).isEqualTo("COMPLETED");
        assertThat(store.find("knowledge-failure", "read").result()).contains("DEPENDENCY_UNAVAILABLE", "readOnly").doesNotContain("OUTCOME_UNKNOWN", "PERMISSION_DENIED");
        runner.execute(request("knowledge-failure", null, null, "find"));
        assertThat(effects).hasValue(1);
    }

    @Test
    void knowledgeTimeoutAndLateSuccessKeepFirstTerminalResult() throws Exception {
        properties.setToolTimeoutMs(100);
        var release = new java.util.concurrent.CountDownLatch(1);
        var returned = new java.util.concurrent.CountDownLatch(1);
        ToolCallback read = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("milvusKnowledgeSearch").description("read").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) {
                effects.incrementAndGet();
                while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) { } }
                returned.countDown(); return "late success";
            }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:milvusKnowledgeSearch", new SupervisorCapability("local:milvusKnowledgeSearch", "local", "milvusKnowledgeSearch", "1", read)));
        when(model.call(anyList(), anyList())).thenReturn(calls(call("read", "milvusKnowledgeSearch", "{}")), new AssistantMessage("timeout"));
        try {
            assertThat(runner.execute(request("knowledge-timeout", null, null, "find")).status()).isEqualTo("COMPLETED");
            assertThat(store.find("knowledge-timeout", "read").result()).contains("TIMEOUT").doesNotContain("OUTCOME_UNKNOWN");
            release.countDown(); assertThat(returned.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            store.completeKnowledgeRead("knowledge-timeout", "read", "late success", null);
            assertThat(store.find("knowledge-timeout", "read").result()).contains("TIMEOUT").doesNotContain("late success");
            runner.execute(request("knowledge-timeout", null, null, "find"));
            assertThat(effects).hasValue(1);
        } finally { release.countDown(); }
    }

    @Test
    void knowledgeMissingCredentialsIsNotPermissionOrUnknownAndOrdinarySupervisorNeedsNoRag() {
        ToolCallback read = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("milvusKnowledgeSearch").description("read").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { throw new com.bkanent.agent.security.AgentRagAuthenticationException("AUTHENTICATION_REQUIRED"); }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:milvusKnowledgeSearch", new SupervisorCapability("local:milvusKnowledgeSearch", "local", "milvusKnowledgeSearch", "1", read)));
        when(model.call(anyList(), anyList())).thenReturn(calls(call("read", "milvusKnowledgeSearch", "{}")), new AssistantMessage("authenticate"), new AssistantMessage("ordinary response"));
        runner.execute(request("knowledge-missing", null, null, "find"));
        assertThat(store.find("knowledge-missing", "read").result()).contains("AUTHENTICATION_REQUIRED").doesNotContain("PERMISSION_DENIED", "OUTCOME_UNKNOWN");
        assertThat(runner.execute(request("no-knowledge", null, null, "hello")).finalAnswer()).isEqualTo("ordinary response");
    }

    @Test
    void abandonedKnowledgeReadIsDefiniteFailureWithoutResendingOriginalClaim() {
        store.register("read-recovery", request("read-recovery", null, null, "find"));
        store.prepare("read-recovery", "read", "local:milvusKnowledgeSearch", "{}");
        store.claim("read-recovery", "read");
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:milvusKnowledgeSearch", capability("milvusKnowledgeSearch")));
        when(model.call(anyList(), anyList())).thenReturn(calls(call("read", "milvusKnowledgeSearch", "{}")), new AssistantMessage("not resent"));
        assertThat(runner.execute(request("read-recovery", null, null, "find")).status()).isEqualTo("COMPLETED");
        assertThat(store.find("read-recovery", "read").result()).contains("DEPENDENCY_UNAVAILABLE").doesNotContain("OUTCOME_UNKNOWN");
        assertThat(effects).hasValue(0);
    }

    @Test
    void workerUsesBoundedDedicatedLeaseWithoutCredentialsInModelOrCheckpoint() throws Exception {
        var auth = mock(com.bkanent.common.rpc.AuthPermissionRpcService.class);
        when(auth.resolvePrincipal("transport-only-value")).thenReturn(new com.bkanent.common.rpc.AuthenticatedPrincipal(1L));
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("auth", auth);
        var leases = new com.bkanent.agent.security.AgentRagCredentialLeaseStore(new com.bkanent.agent.config.AgentRagConsumerProperties(), beans.getBeanProvider(com.bkanent.common.rpc.AuthPermissionRpcService.class));
        factory.setRagCredentials(leases); runner.setRagCredentials(leases);
        try (var header = leases.openAuthorization("Bearer transport-only-value")) { leases.capture("async-read", "1"); }
        ToolCallback read = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("milvusKnowledgeSearch").description("read").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { assertThat(leases.credentials("1").accessToken()).isEqualTo("transport-only-value"); effects.incrementAndGet(); return "safe result"; }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:milvusKnowledgeSearch", new SupervisorCapability("local:milvusKnowledgeSearch", "local", "milvusKnowledgeSearch", "1", read)));
        when(model.call(anyList(), anyList())).thenAnswer(call -> {
            assertThat(mapper.writeValueAsString(call.getArgument(0))).doesNotContain("transport-only-value");
            return effects.get() == 0 ? calls(call("read", "milvusKnowledgeSearch", "{}")) : new AssistantMessage("done");
        });
        assertThat(runner.execute(request("async-read", null, null, "find")).status()).isEqualTo("COMPLETED");
        assertThat(mapper.writeValueAsString(state("async-read"))).doesNotContain("transport-only-value");
        assertThat(store.json(store.originalRequest("async-read"))).doesNotContain("transport-only-value");
        try (var lease = leases.openLease("async-read", "1")) { assertThatThrownBy(() -> leases.credentials("1")).hasMessage("AUTHENTICATION_REQUIRED"); }
        assertThat(effects).hasValue(1);
    }

    @Test
    void completedRunRejectsCrossSessionChangedBodyAndPolicyBeforeAnyFurtherEffect() {
        when(model.call(anyList(), anyList())).thenReturn(calls(call("original", "search", "{}")), new AssistantMessage("done"));
        var original = request("identity", null, null, "find");
        var completed = runner.execute(original);
        clearInvocations(model, events, catalog, memory);
        for (var conflicting : List.of(
                new SupervisorTaskRequest("other-session", "1", "identity", "trace", "find", Map.of(), "api", false),
                request("identity", null, null, "changed"),
                new SupervisorTaskRequest("session", "1", "identity", "trace", "find", Map.of("requireApproval", true), "api", false))) {
            assertThatThrownBy(() -> runner.execute(conflicting)).hasMessage("RUN_REQUEST_ID_CONFLICT");
        }
        verifyNoInteractions(model, events, catalog, memory);
        assertThat(effects).hasValue(1);
        assertThat(runner.execute(original)).isEqualTo(completed);
    }

    @Test
    void unknownRunRejectsChangedInputWithoutResendingItsOriginalCall() {
        ToolCallback failing = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return capability("search").callback().getToolDefinition(); }
            public String call(String input) { effects.incrementAndGet(); throw new IllegalStateException("unknown remote effect"); }
        };
        when(catalog.snapshot("1", true)).thenReturn(Map.of("local:search", new SupervisorCapability("local:search", "local", "search", "1", failing)));
        when(model.call(anyList(), anyList())).thenReturn(calls(call("original-unknown", "search", "{}")));
        runner.execute(request("unknown-identity", null, null, "find"));
        assertThatThrownBy(() -> runner.execute(request("unknown-identity", null, null, "different"))).hasMessage("RUN_REQUEST_ID_CONFLICT");
        assertThat(store.find("unknown-identity", "original-unknown").status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(effects).hasValue(1);
        verify(model, times(1)).call(anyList(), anyList());
    }

    @Test
    void chatContinuationSessionIsTakenFromOriginalOwnerNotSuppliedModelIdentity() {
        store.register("chat-run", request("chat-run", null, null, "find"));
        assertThat(runner.sessionIdForChat("chat-run", null, null, "1")).isEqualTo("session");
        assertThatThrownBy(() -> runner.sessionIdForChat("chat-run", null, "another", "1")).hasMessage("CONTINUATION_SESSION_MISMATCH");
        assertThatThrownBy(() -> runner.sessionIdForChat("chat-run", null, null, "2")).hasMessage("CONTINUATION_OWNER_MISMATCH");
        assertThat(runner.sessionIdForChat(null, null, null, "1")).isNotBlank();
        assertThat(runner.sessionIdForChat(null, "chat-run", null, "1")).isEqualTo("session");
        assertThatThrownBy(() -> runner.sessionIdForChat(null, "chat-run", "other", "1")).hasMessage("RUN_REQUEST_ID_CONFLICT");
        verifyNoInteractions(model, events);
    }

    private ToolLoopState state(String id) { return factory.stateOf(graph.lastStateOf(com.alibaba.cloud.ai.graph.RunnableConfig.builder().threadId(id).build()).orElseThrow().state()); }
    private SupervisorTaskRequest request(String id, SkillSelection skill, String continueId, String message) {
        return new SupervisorTaskRequest("session", "1", id, "trace", message, Map.of(), "api", false, skill, continueId, null);
    }
    private static String authzNonce(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
