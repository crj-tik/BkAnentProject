package com.bkanent.agent.orchestration;

import com.bkanent.agent.config.AgentRagConsumerProperties;
import com.bkanent.agent.entity.AgentWorkflowCheckpointEntity;
import com.bkanent.agent.graph.official.*;
import com.bkanent.agent.mapper.AgentWorkflowCheckpointMapper;
import com.bkanent.agent.memory.*;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.security.AgentRagCredentialLeaseStore;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.*;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.memory.runtime.MemoryWorkingSetCache;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.config.ReferenceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Graph, persisted checkpoint saver, reference journal, current actor lease and thin runtime.
 * Only the remote Memory endpoint is a contract fixture; its actual SQL/security is tested in memory-service. */
class SupervisorMemoryIntegrationTest {
    @Test void explicitCloseTriggersSameCoordinatorBelowSoftThresholdAndReopenIsVersioned() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.setCompressionEnabled(true); f.properties.setRecentMessages(1);
            when(f.model.call(anyList(), anyList())).thenAnswer(inv -> {
                List<ToolCallback> tools = inv.getArgument(1);
                if (tools.isEmpty()) { f.summaryCalls.incrementAndGet(); return new AssistantMessage("{\"items\":[{\"kind\":\"FACT\",\"text\":\"用户事项\",\"evidence\":[0]}],\"entities\":[]}"); }
                return new AssistantMessage("done");
            });
            assertEquals("COMPLETED", f.execute("start", "开始买房事项", new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房")).status());
            var matter = f.remote.snapshot.matters().get(0);
            assertEquals(MemoryMatter.State.ACTIVE, matter.state(), "assistant done is not user confirmation");
            assertEquals(0, f.summaryCalls.get());
            assertEquals("COMPLETED", f.execute("close", "确认关闭", new MemoryMatterIntent(MemoryMatterIntent.Action.CLOSE, matter.matterId(), 1, null)).status());
            assertEquals(1, f.summaryCalls.get()); assertFalse(f.remote.snapshot.summaries().isEmpty());
            assertEquals(MemoryMatter.State.CLOSED, f.remote.snapshot.matters().get(0).state());
            assertEquals(2, f.remote.snapshot.summaries().get(0).coveredRange().throughSequence());
            assertEquals("COMPLETED", f.execute("closed-ordinary", "开始另一个事情").status());
            var ordinary = f.state("closed-ordinary").messages.get(0);
            assertNull(ordinary.matterId(), "a closed matter is not silently selected or reopened");
            f.restartIdentity();
            assertEquals("COMPLETED", f.execute("reopen", "继续此事项", new MemoryMatterIntent(MemoryMatterIntent.Action.REOPEN, matter.matterId(), 2, null)).status());
            assertEquals(MemoryMatter.State.REOPENED, f.remote.snapshot.matters().get(0).state());
            assertEquals(3, f.remote.snapshot.matters().get(0).version().revision());
            assertNull(f.state("start").messages.get(0).matterId(), "old archived user event is never retagged");
            assertEquals(matter.matterId(), f.state("start").messages.get(1).matterId());
        }
    }
    @Test void typedIntentCannotSilentlySucceedWhenMemoryIsOff() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.setEnabled(false);
            assertEquals("FAILED", f.execute("disabled-intent", "开始事项", new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房")).status());
            assertEquals(0, f.remote.count("transitionMatter")); verifyNoInteractions(f.model);
        }
    }
    @Test void deliveryOnlyModeSkipsPromptReadsAndFinishesAnAcceptedDeliveryAfterCutoff() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.setReadEnabled(false);
            when(f.model.call(anyList(), anyList())).thenAnswer(invocation -> {
                f.inputs.add(invocation.getArgument(0));
                assertEquals(0, f.remote.count("readWorkingSet"), "delivery-only mode must not load memory before the prompt");
                f.properties.setDeliveryEnabled(false); // close new delivery admission after this run was accepted
                return new AssistantMessage("本轮完成");
            });

            var response = f.execute("delivery-only", "独立请求");

            assertEquals("COMPLETED", response.status());
            assertEquals("DELIVERY_ONLY", f.state("delivery-only").memoryMode);
            assertTrue(f.state("delivery-only").memoryDeliveryAccepted);
            assertEquals(1, f.remote.count("readWorkingSet"), "the writer baseline is loaded only when the completed turn is delivered");
            assertEquals(1, f.remote.count("requestReceivePermit"));
            assertEquals(1, f.remote.count("deliverMessages"));
            assertTrue(f.inputs.get(0).toString().contains("独立请求"));
        }
    }
    @Test void matterPreferenceIsInjectedAndRecheckedBeforeFinalOutput() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remote.preferenceValue = "100万";
            when(f.model.call(anyList(), anyList())).thenAnswer(call -> {
                f.inputs.add(call.getArgument(0));
                f.remote.preferenceRevision++;
                return new AssistantMessage("stale answer using an outdated preference");
            });
            var response = f.execute("preference", "开始买房", new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
            assertEquals("FAILED", response.status());
            assertEquals("MEMORY_VERSION_CONFLICT", response.governanceMetadata().get("errorCode"));
            assertEquals(1, f.remote.count("assemblePreferences"));
            assertTrue(f.inputs.get(0).toString().contains("100万"));
            for (String json : f.jdbc.queryForList("SELECT snapshot_json FROM agent_workflow_checkpoint", String.class)) {
                assertFalse(json.contains("stale answer using an outdated preference"));
            }
        }
    }
    @Test void sameMatterReusesSelectionAcrossModelToolRounds() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remote.preferenceValue = "100万";
            f.localToolThenAnswer();
            var response = f.execute("preference-refresh", "开始买房", new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
            assertEquals("COMPLETED", response.status(), response.governanceMetadata().toString());
            assertEquals(1, f.remote.count("assemblePreferences"), "the same valid selection is reused across model/tool rounds");
            assertTrue(f.inputs.get(0).toString().contains("100万"));
            assertTrue(f.inputs.get(1).toString().contains("100万"));
        }
    }
    @Test void matterExperiencesAreInjectedAndReusedAcrossModelToolRounds() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remote.experienceValue = "买房预算建议先核对总价和月供";
            f.localToolThenAnswer();
            var response = f.execute("experience-selection", "开始买房", new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
            assertEquals("COMPLETED", response.status(), response.governanceMetadata().toString());
            assertEquals(1, f.remote.count("searchExperiences"), "the same matter reuses experience selection across model/tool rounds");
            assertTrue(f.inputs.get(0).toString().contains("买房预算建议先核对总价和月供"));
            assertTrue(f.inputs.get(1).toString().contains("买房预算建议先核对总价和月供"));
            assertTrue(f.inputs.get(0).toString().contains("不得把经验转换为业务流程"));
        }
    }
    @Test void deactivatedExperienceCannotAuthorizeModelOutput() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remote.experienceValue = "旧经验";
            when(f.model.call(anyList(), anyList())).thenAnswer(call -> {
                f.remote.experienceRevision++;
                return new AssistantMessage("根据旧经验生成的回答");
            });
            var response = f.execute("experience-revoked", "开始买房", new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
            assertEquals("FAILED", response.status());
            assertEquals("MEMORY_VERSION_CONFLICT", response.governanceMetadata().get("errorCode"));
            for (String json : f.jdbc.queryForList("SELECT snapshot_json FROM agent_workflow_checkpoint", String.class))
                assertFalse(json.contains("根据旧经验生成的回答"));
            for (String json : f.jdbc.queryForList("SELECT snapshot_json FROM agent_workflow_checkpoint", String.class))
                assertFalse(json.contains("旧经验"), "private experience bodies are transient and never enter graph checkpoints");
        }
    }
    @Test void stalePreferenceCannotAuthorizeAToolResultAfterThePreferenceChangesDuringExecution() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remote.preferenceValue = "100万";
            f.localToolThenAnswer();
            f.onToolCall = () -> { f.remote.preferenceRevision++; f.remote.preferenceValue = "200万"; };
            var response = f.execute("preference-change-during-tool", "开始买房", new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
            assertEquals("FAILED", response.status());
            assertEquals("MEMORY_VERSION_CONFLICT", response.governanceMetadata().get("errorCode"));
            assertEquals(1, f.remote.count("assemblePreferences"));
            assertEquals(1, f.effects.get());
            assertTrue(((List<?>) response.governanceMetadata().get("calls")).isEmpty());
        }
    }
    @Test void nextUserTurnAssemblesTheCurrentPreferenceForTheSameActiveMatter() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remote.preferenceValue = "100万";
            when(f.model.call(anyList(), anyList())).thenAnswer(call -> {
                f.inputs.add(call.getArgument(0));
                return new AssistantMessage("done");
            });
            assertEquals("COMPLETED", f.execute("preference-turn-1", "开始买房", new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算")).status());
            f.remote.preferenceRevision++;
            f.remote.preferenceValue = "200万";
            assertEquals("COMPLETED", f.execute("preference-turn-2", "预算调整").status());
            assertEquals(2, f.remote.count("assemblePreferences"));
            assertTrue(f.inputs.get(0).toString().contains("100万"));
            assertTrue(f.inputs.get(1).toString().contains("200万"));
        }
    }
    @Test void explicitCurrentCorrectionOverridesAnOlderRetrievedPreference() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remote.preferenceValue = "100万";
            when(f.model.call(anyList(), anyList())).thenAnswer(call -> {
                f.inputs.add(call.getArgument(0));
                return new AssistantMessage("done");
            });
            assertEquals("COMPLETED", f.execute("preference-correction-start", "开始买房",
                    new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算")).status());
            assertEquals("COMPLETED", f.execute("preference-correction", "更正：本轮预算改为200万，之前的100万不再适用。").status());
            assertEquals(2, f.remote.count("assemblePreferences"), "the new user event refreshes the local selection");
            List<String> currentMessages = f.inputs.get(1).stream().map(Message::getText).filter(Objects::nonNull).toList();
            String currentInput = String.join("\n", currentMessages);
            assertTrue(currentInput.contains("100万"), "the authorized retrieved preference remains visible as history data");
            assertTrue(currentInput.contains("本轮预算改为200万，之前的100万不再适用"));
            assertTrue(currentInput.contains("当前用户本轮明确表达、政策、授权与审批规则优先"));
            int retrievedPreference = indexContaining(currentMessages, "偏好数据");
            int currentCorrection = indexContaining(currentMessages, "更正：本轮预算改为200万");
            assertTrue(retrievedPreference >= 0 && currentCorrection > retrievedPreference,
                    "the current user correction follows older preference data in the model window");
        }
    }
    @Test void referenceControlUsesMemoryRecallNodeAndNoBusinessExecution() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.model.call(anyList(), anyList())).thenAnswer(inv -> {
                List<Message> input = inv.getArgument(0); f.inputs.add(input);
                if (f.inputs.size() == 1) return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("reference-1", "function", SupervisorMemoryReferentResolver.TOOL_NAME, "{\"reference\":\"那套房\"}"))).build();
                assertTrue(input.toString().contains("NEEDS_CLARIFICATION")); return new AssistantMessage("请说明所指房源");
            });
            assertEquals("COMPLETED", f.execute("reference", "那套房呢").status());
            assertEquals(1, f.remote.count("queryEntities")); assertEquals(0, f.effects.get());
            assertTrue(f.state("reference").messages.stream().anyMatch(m -> "tool".equals(m.role()) && m.responses().get(0).name().equals(SupervisorMemoryReferentResolver.TOOL_NAME)));
        }
    }
    @Test void actualModelNodesArchiveOnceReuseWindowAndNeverPersistCredential() throws Exception {
        try (Fixture f = new Fixture()) {
            f.localToolThenAnswer();
            var response = f.execute("run", "first input");
            assertEquals("COMPLETED", response.status());
            assertEquals(1, f.effects.get());
            assertEquals(4, f.remote.snapshot.messages().size());
            assertEquals(1, f.remote.count("readWorkingSet"));
            assertTrue(f.remote.count("checkBoundary") >= 4);
            assertEquals(4, f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_delivery WHERE state='RECEIVED'", Integer.class));
            assertEquals("COMPLETED", f.store.find("run", "call-1").status());
            for (String json : f.jdbc.queryForList("SELECT snapshot_json FROM agent_workflow_checkpoint", String.class)) assertFalse(json.contains("transport-only-value"));
            assertFalse(f.store.json(f.store.originalRequest("run")).contains("transport-only-value"));
            assertEquals(0, f.remote.activeWriters);
        }
    }

    @Test void secondRunRestoresSameConversationWithoutDuplicatingCurrentInput() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.model.call(anyList(), anyList())).thenAnswer(call -> {
                List<Message> messages = call.getArgument(0);
                f.inputs.add(messages);
                return new AssistantMessage("answer");
            });
            assertEquals("COMPLETED", f.execute("run-1", "unique first input").status());
            assertEquals("COMPLETED", f.execute("run-2", "unique second input").status());
            String second = f.inputs.get(1).toString();
            assertEquals(1, occurrences(second, "unique second input"));
            assertEquals(1, occurrences(second, "unique first input"));
            assertEquals(1, f.remote.count("readWorkingSet"));
        }
    }

    @Test void completedBusinessToolIsNotRerunWhenLostMemoryReceiptIsRecovered() throws Exception {
        try (Fixture f = new Fixture()) {
            f.localToolThenAnswer();
            f.remote.loseToolReceipt = true;
            var failed = f.execute("run", "find");
            assertEquals("FAILED", failed.status());
            assertEquals("COMPLETED", f.store.find("run", "call-1").status());
            assertEquals(1, f.effects.get());
            assertTrue(f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_delivery WHERE state='PENDING'", Integer.class) > 0);
            f.remote.loseToolReceipt = false;
            f.restartIdentity();
            assertThrows(com.bkanent.agent.security.AgentRagAuthenticationException.class, () -> f.runner.execute(f.request("run", "find")));
            assertEquals("COMPLETED", f.execute("run", "find").status());
            assertEquals(1, f.effects.get());
            assertEquals(4, f.remote.snapshot.messages().size(), "missing reply retries the same immutable event");
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_delivery WHERE state='PENDING'", Integer.class));
        }
    }

    @Test void sourceRevokedDuringModelCannotEscapeIntoOutputOrCheckpoint() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.model.call(anyList(), anyList())).thenAnswer(call -> { f.remote.revoked = true; return new AssistantMessage("stale private output"); });
            var response = f.execute("run", "input");
            assertEquals("FAILED", response.status());
            assertEquals("MEMORY_WITHDRAWN", response.governanceMetadata().get("errorCode"));
            assertEquals("记忆资料当前不可安全使用，已保留执行记录，请重新认证或稍后恢复。", response.finalAnswer());
            assertTrue(((List<?>) response.governanceMetadata().get("calls")).isEmpty());
            for (String json : f.jdbc.queryForList("SELECT snapshot_json FROM agent_workflow_checkpoint", String.class)) assertFalse(json.contains("stale private output"));
            assertEquals(0, f.effects.get());
        }
    }

    @Test void v2CheckpointCannotFallBackToRawBodyWhenFeatureIsDisabled() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.model.call(anyList(), anyList())).thenReturn(new AssistantMessage("private old answer"));
            f.execute("run", "input");
            f.properties.setEnabled(false);
            try (var auth = f.identities.openAuthorization("Bearer transport-only-value")) {
                assertEquals(MemoryErrorCode.MEMORY_UNAVAILABLE, assertThrows(MemoryException.class, () -> f.runner.current("run", "1")).code());
            }
        }
    }

    @Test void disabledForNewRunKeepsOriginalPathWithZeroMemoryRpc() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.setEnabled(false); f.localToolThenAnswer();
            assertEquals("COMPLETED", f.execute("run", "original request").status());
            assertEquals(1, f.effects.get());
            assertTrue(f.remote.calls.isEmpty());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_delivery", Integer.class));
            var tools = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(f.model, atLeastOnce()).call(anyList(), tools.capture());
            for (List<?> callbacks : tools.getAllValues()) assertTrue(callbacks.stream().noneMatch(tool -> ((ToolCallback) tool).getToolDefinition().name().equals(SupervisorMemoryResultView.TOOL_NAME)));
        }
    }

    @Test void actualCompressionModelGetsNoToolsAndWindowAdoptsOnlyCommittedCoverage() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.setCompressionEnabled(true); f.properties.setSoftTokens(1); f.properties.setRecentMessages(1);
            f.localToolThenAnswer();
            assertEquals("COMPLETED", f.execute("run", "first input").status());
            assertEquals(1, f.remote.count("submitSummary"));
            assertEquals(1, f.summaryCalls.get());
            assertEquals(1, f.remote.snapshot.summaries().get(0).coveredRange().throughSequence());
            assertEquals(1, f.effects.get());
            assertEquals(4, f.state("run").messages.size(), "checkpoint keeps execution facts independently from adopted model window");
        }
    }

    @Test void hardBudgetStopsBeforeModelAndKeepsCheckpointAndJournal() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.setHardTokens(1);
            var result = f.execute("run", "input");
            assertEquals("FAILED", result.status());
            assertEquals("MEMORY_CONTEXT_BUDGET_EXCEEDED", result.governanceMetadata().get("errorCode"));
            verifyNoInteractions(f.model);
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_delivery", Integer.class));
            assertEquals(1, f.state("run").messages.size());
        }
    }

    @Test void timedOutSummaryKeepsOriginalWindowAndCannotCommitAfterCancellation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.setCompressionEnabled(true); f.properties.setSoftTokens(1); f.properties.setRecentMessages(1);
            f.properties.setSummaryTimeoutMs(1000);
            CountDownLatch interrupted = new CountDownLatch(1);
            f.onSummary = () -> {
                try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("summary cancelled", exception);
                }
            };
            f.localToolThenAnswer();
            assertEquals("COMPLETED", f.execute("run", "first input").status());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "timed out candidate generation is cancelled");
            assertEquals(0, f.remote.count("submitSummary"));
            assertTrue(f.remote.snapshot.summaries().isEmpty());
            assertEquals(4, f.remote.snapshot.messages().size());
            assertEquals(4, f.state("run").messages.size());
            assertEquals(1, f.effects.get());
        }
    }

    @Test void initialOptionalDependencyFailureDoesNotMasqueradeAsLoadedHistory() throws Exception {
        try (Fixture f = new Fixture()) {
            f.remote.unavailable = true;
            when(f.model.call(anyList(), anyList())).thenAnswer(call -> {
                f.inputs.add(call.getArgument(0));
                return new AssistantMessage("independent answer");
            });
            var result = f.execute("run", "independent input");
            assertEquals("COMPLETED", result.status());
            assertEquals("MEMORY_UNAVAILABLE", result.governanceMetadata().get("memoryStatus"));
            assertEquals("NOT_APPLIED", f.state("run").memoryMode);
            assertTrue(f.inputs.get(0).stream().filter(SystemMessage.class::isInstance).map(Message::getText)
                    .anyMatch(text -> text.contains("可选历史记忆不可用且未应用") && text.contains("不得猜测历史事实")));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_delivery", Integer.class));
        }
    }

    @Test void missingAndUnavailableHistoricalPreferencesHaveDistinctPromptStates() throws Exception {
        try (Fixture noMatch = new Fixture()) {
            when(noMatch.model.call(anyList(), anyList())).thenAnswer(call -> {
                noMatch.inputs.add(call.getArgument(0));
                return new AssistantMessage("请补充预算");
            });
            var response = noMatch.execute("preference-no-match", "开始买房事项，预算按之前确认的金额继续",
                    new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
            assertEquals("COMPLETED", response.status());
            assertEquals("NO_MATCH", noMatch.state("preference-no-match").memoryPreferenceStatus);
            assertTrue(noMatch.inputs.get(0).stream().filter(SystemMessage.class::isInstance).map(Message::getText)
                    .anyMatch(text -> text.contains("未找到与本轮请求匹配") && text.contains("不得猜测偏好")));
        }
        try (Fixture unavailable = new Fixture()) {
            unavailable.remote.preferenceUnavailable = true;
            when(unavailable.model.call(anyList(), anyList())).thenAnswer(call -> {
                unavailable.inputs.add(call.getArgument(0));
                return new AssistantMessage("请补充预算");
            });
            var response = unavailable.execute("preference-unavailable", "开始买房事项，预算按之前确认的金额继续",
                    new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
            assertEquals("COMPLETED", response.status());
            assertEquals("MEMORY_UNAVAILABLE", unavailable.state("preference-unavailable").memoryPreferenceStatus);
            assertTrue(unavailable.inputs.get(0).stream().filter(SystemMessage.class::isInstance).map(Message::getText)
                    .anyMatch(text -> text.contains("历史偏好查询不可用") && text.contains("不得猜测或沿用未经本轮确认的偏好")));
        }
    }

    @Test void historicalMemoryCannotBeForwardedToExternalCollaboratorWithoutGrant() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.model.call(anyList(), anyList())).thenReturn(new AssistantMessage("first result"));
            assertEquals("COMPLETED", f.execute("run-1", "private input").status());
            AtomicInteger externalEffects = new AtomicInteger();
            ToolCallback external = new ToolCallback() {
                public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("send").description("external collaboration").inputSchema("{\"type\":\"object\"}").build(); }
                public String call(String arguments) { externalEffects.incrementAndGet(); return "sent"; }
            };
            when(f.catalog.snapshot("1", true)).thenReturn(Map.of("a2a:partner:send", new SupervisorCapability("a2a:partner:send", "a2a", "partner", "1", external)));
            when(f.model.call(anyList(), anyList())).thenReturn(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-external", "function", "send", "{}"))).build());
            var response = f.execute("run-2", "continue with partner");
            assertEquals("FAILED", response.status());
            assertEquals("MEMORY_FORBIDDEN", response.governanceMetadata().get("errorCode"));
            assertEquals(0, externalEffects.get());
            assertNull(f.store.find("run-2", "call-external"));
        }
    }

    @Test void restoredCheckpointRechecksSourceAuthorizationBeforeReusingItsModelWindow() throws Exception {
        try (Fixture f = new Fixture()) {
            f.localToolThenAnswer();
            assertEquals("COMPLETED", f.execute("run", "input").status());
            var restored = f.state("run");
            assertFalse(restored.adoptedMemorySources.isEmpty(), "persisted run state pins the source dependencies it adopted");
            f.remote.revoked = true;
            int callsBeforeResume = mockingDetails(f.model).getInvocations().size();
            MemoryException error;
            try (var scope = f.identities.openAuthorization("Bearer transport-only-value")) {
                error = assertThrows(MemoryException.class, () -> f.lifecycle.beforeModel(restored, "system"));
            }
            assertEquals(MemoryErrorCode.WITHDRAWN, error.code());
            assertEquals(callsBeforeResume, mockingDetails(f.model).getInvocations().size(),
                    "stale checkpoint memory must be rejected before another model call");
        }
    }

    @Test void revocationAfterToolEffectWithholdsToolResultEventUntilRecovery() throws Exception {
        try (Fixture f = new Fixture()) {
            f.onToolCall = () -> f.remote.revoked = true;
            when(f.model.call(anyList(), anyList())).thenReturn(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "find", "{}"))).build());
            var response = f.execute("run", "input");
            assertEquals("FAILED", response.status());
            assertEquals("MEMORY_WITHDRAWN", response.governanceMetadata().get("errorCode"));
            assertTrue(((List<?>) response.governanceMetadata().get("calls")).isEmpty(), "revoked tool content is withheld from the task response");
            assertTrue(response.artifactIds().isEmpty(), "memory-derived artifacts are withheld after source revocation");
            assertEquals(1, f.effects.get(), "memory recovery cannot re-run the completed local action");
            var events = org.mockito.ArgumentCaptor.forClass(SessionStreamEvent.class);
            verify(f.stream, atLeastOnce()).publish(events.capture());
            assertFalse(events.getAllValues().stream().anyMatch(event -> "tool.completed".equals(event.eventType())));
            assertEquals("COMPLETED", f.store.find("run", "call-1").status());
            assertTrue(f.remote.snapshot.messages().stream().noneMatch(message -> message.role() == MemoryMessage.Role.TOOL));
            assertEquals(1, f.state("run").results.size(), "the tool result remains in the recoverable graph checkpoint");
        }
    }

    @Test void largeResultIsArchivedCompletelyAndRecalledThroughDedicatedBoundedGraphNode() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.setToolInlineChars(256); f.properties.setRecallChars(2048);
            String complete = "detail-value-".repeat(1000);
            f.toolResult = f.mapper.writeValueAsString(Map.of("detail", complete, "count", 12));
            AtomicInteger turns = new AtomicInteger();
            when(f.model.call(anyList(), anyList())).thenAnswer(invocation -> {
                List<Message> messages = invocation.getArgument(0); List<ToolCallback> tools = invocation.getArgument(1);
                assertTrue(tools.stream().anyMatch(t -> t.getToolDefinition().name().equals(SupervisorMemoryResultView.TOOL_NAME)));
                int turn = turns.getAndIncrement();
                if (turn == 0) return toolCall("call-1", "find", "{}");
                ToolResponseMessage response = (ToolResponseMessage) messages.get(messages.size() - 1);
                var data = f.mapper.readTree(response.getResponses().get(0).responseData());
                if (turn == 1) {
                    assertEquals("REFERENCED_COMPLETE", data.get("dataForm").asText());
                    assertFalse(data.toString().contains(complete));
                    return toolCall("recall-1", SupervisorMemoryResultView.TOOL_NAME, f.mapper.writeValueAsString(Map.of(
                            "eventId", data.get("eventId").asText(), "callId", data.get("callId").asText(), "referenceId", data.get("referenceId").asText(),
                            "pointer", "/detail", "offset", 0, "limit", 4096)));
                }
                assertTrue(data.has("chunk"), "bounded recall stays inline instead of recursively becoming another reference");
                assertTrue(data.get("chunk").asText().startsWith("\"detail-value-"));
                assertTrue(data.toString().length() <= 2048); assertTrue(data.get("hasMore").asBoolean());
                return new AssistantMessage("done");
            });
            assertEquals("COMPLETED", f.execute("run", "find").status());
            assertEquals(1, f.effects.get()); assertNull(f.store.find("run", "recall-1"));
            var raw = f.remote.snapshot.messages().stream().flatMap(m -> m.toolResponses().stream()).filter(r -> r.toolName().equals("find")).findFirst().orElseThrow();
            assertEquals(complete, raw.responseData().properties().get("detail").scalar());
            assertEquals(MemoryToolResponse.DataForm.INLINE_COMPLETE, raw.dataForm());
            verify(f.journal).readReceived(any(), any());
            assertEquals(2, f.state("run").toolCalls);
        }
    }

    @Test void coldRunCanRecallCompressedPreviousResultWithoutRepeatingBusinessTool() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.setToolInlineChars(256); f.toolResult = "{\"detail\":\"" + "full-value".repeat(1000) + "\"}";
            f.localToolThenAnswer(); assertEquals("COMPLETED", f.execute("old-run", "find").status());
            f.restartIdentity(); f.properties.setCompressionEnabled(true); f.properties.setSoftTokens(1); f.properties.setRecentMessages(1);
            AtomicInteger turns = new AtomicInteger();
            when(f.model.call(anyList(), anyList())).thenAnswer(invocation -> {
                List<ToolCallback> tools = invocation.getArgument(1);
                if (tools.isEmpty()) return new AssistantMessage("{\"items\":[{\"kind\":\"FACT\",\"text\":\"保留原结果引用\",\"evidence\":[0]}],\"entities\":[]}");
                if (turns.getAndIncrement() == 0) {
                    var anchor = f.remote.snapshot.summaries().get(0).toolResults().get(0);
                    return toolCall("old-recall", SupervisorMemoryResultView.TOOL_NAME, f.mapper.writeValueAsString(Map.of(
                            "eventId", anchor.source().sourceEventId(), "callId", anchor.callId(), "referenceId", SupervisorMemoryResultView.referenceId(anchor),
                            "pointer", "/detail", "limit", 40)));
                }
                List<Message> messages = invocation.getArgument(0);
                assertTrue(messages.toString().contains("full-value")); return new AssistantMessage("done");
            });
            assertEquals("COMPLETED", f.execute("new-run", "查看上一结果").status());
            assertEquals(1, f.effects.get());
            assertEquals(1, f.remote.snapshot.summaries().get(0).toolResults().size(), "later compaction preserves prior result anchors");
            verify(f.journal).readReceived(any(), any());
        }
    }

    @Test void withdrawalAfterReferenceMetadataPreventsBodyReadAndSameRunResumesWithoutBusinessReplay() throws Exception {
        try (Fixture f = new Fixture()) {
            f.properties.setToolInlineChars(256); f.toolResult = "{\"detail\":\"" + "x".repeat(1000) + "\"}";
            f.localToolThenAnswer(); assertEquals("COMPLETED", f.execute("old", "find").status());
            var message = f.remote.snapshot.messages().stream().filter(m -> m.role() == MemoryMessage.Role.TOOL).findFirst().orElseThrow();
            var anchor = new SupervisorMemoryResultView(f.mapper, 256, 2048).anchors(List.of(message)).get(0);
            String args = f.mapper.writeValueAsString(Map.of("eventId", anchor.source().sourceEventId(), "callId", anchor.callId(), "referenceId", SupervisorMemoryResultView.referenceId(anchor)));
            AtomicInteger turns = new AtomicInteger();
            when(f.model.call(anyList(), anyList())).thenAnswer(invocation -> turns.getAndIncrement() == 0
                    ? toolCall("recall", SupervisorMemoryResultView.TOOL_NAME, args) : new AssistantMessage("done"));
            doAnswer(invocation -> { Object source = invocation.callRealMethod(); f.remote.revoked = true; return source; }).when(f.journal).receivedSource(any(), anyString());
            var failed = f.execute("new", "补忆");
            assertEquals("FAILED", failed.status()); assertEquals("MEMORY_WITHDRAWN", failed.governanceMetadata().get("errorCode"));
            verify(f.journal, never()).readReceived(any(), any());
            assertEquals("MemoryRecall", f.state("new").memoryResumeNode);
            f.remote.revoked = false; f.restartIdentity();
            assertEquals("COMPLETED", f.execute("new", "补忆").status());
            verify(f.journal).readReceived(any(), any()); assertEquals(1, f.effects.get());
        }
    }

    @Test void unknownReferenceReturnsSafeReadOnlyFailureWithoutOpeningAnyBody() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger turns = new AtomicInteger();
            when(f.model.call(anyList(), anyList())).thenAnswer(invocation -> {
                if (turns.getAndIncrement() == 0) return toolCall("missing", SupervisorMemoryResultView.TOOL_NAME,
                        f.mapper.writeValueAsString(Map.of("eventId", "a".repeat(64), "callId", "call", "referenceId", "r-" + "b".repeat(64))));
                List<Message> messages = invocation.getArgument(0); assertTrue(messages.toString().contains("NOT_FOUND"));
                return new AssistantMessage("无法获取");
            });
            assertEquals("COMPLETED", f.execute("run", "补忆").status());
            verify(f.journal, never()).readReceived(any(), any()); assertEquals(0, f.effects.get());
        }
    }

    @Test void recallCannotMixWithBusinessBatchAndToolDefinitionsCountTowardHardBudget() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger turns = new AtomicInteger();
            when(f.model.call(anyList(), anyList())).thenAnswer(invocation -> turns.getAndIncrement() == 0
                    ? AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("a", "function", "find", "{}"),
                        new AssistantMessage.ToolCall("b", "function", SupervisorMemoryResultView.TOOL_NAME, "{}"))).build()
                    : new AssistantMessage("done"));
            assertEquals("COMPLETED", f.execute("run", "input").status()); assertEquals(0, f.effects.get());
            assertTrue(f.state("run").messages.toString().contains("CONTROL_BATCH_REJECTED"));
        }
        try (Fixture f = new Fixture()) {
            ToolCallback enormous = new ToolCallback() {
                public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("find").description("large schema").inputSchema("{\"description\":\"" + "x".repeat(100000) + "\",\"type\":\"object\"}").build(); }
                public String call(String args) { fail("must not execute"); return ""; }
            };
            when(f.catalog.snapshot("1", true)).thenReturn(Map.of("local:find", new SupervisorCapability("local:find", "local", "find", "1", enormous)));
            var response = f.execute("run", "small input");
            assertEquals("FAILED", response.status()); assertEquals("MEMORY_CONTEXT_BUDGET_EXCEEDED", response.governanceMetadata().get("errorCode"));
            verifyNoInteractions(f.model);
        }
    }

    @Test void recallUsesExistingGlobalToolCallBudget() throws Exception {
        try (Fixture f = new Fixture()) {
            f.orchestration.setMaxToolCalls(1);
            AtomicInteger turns = new AtomicInteger();
            when(f.model.call(anyList(), anyList())).thenAnswer(invocation -> toolCall("read-" + turns.incrementAndGet(), SupervisorMemoryResultView.TOOL_NAME, "{}"));
            var result = f.execute("run", "补忆");
            assertEquals("FAILED", result.status()); assertEquals("BUDGET_EXHAUSTED", result.governanceMetadata().get("errorCode"));
            assertEquals(0, f.effects.get()); verify(f.journal, never()).readReceived(any(), any());
        }
    }

    static AssistantMessage toolCall(String id, String tool, String args) {
        return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", tool, args))).build();
    }

    private static java.security.KeyPair generateIdentityPair() {
        try { return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (java.security.GeneralSecurityException exception) { throw new IllegalStateException(exception); }
    }

    private static Path privateKeyFile(java.security.KeyPair pair) throws Exception {
        Path file = Files.createTempFile("supervisor-memory-identity-", ".pem");
        file.toFile().deleteOnExit();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(file, pem, StandardCharsets.US_ASCII);
        return file;
    }

    static int occurrences(String text, String needle) { return (text.length() - text.replace(needle, "").length()) / needle.length(); }
    static int indexContaining(List<String> values, String needle) {
        for (int i = 0; i < values.size(); i++) if (values.get(i).contains(needle)) return i;
        return -1;
    }

    static class Fixture implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final OrchestrationStore store = OrchestrationStoreTest.database(mapper);
        final JdbcTemplate jdbc = (JdbcTemplate) org.springframework.test.util.ReflectionTestUtils.getField(store, "jdbc");
        final SupervisorMemoryProperties properties = new SupervisorMemoryProperties();
        final SupervisorModelTurn model = mock(SupervisorModelTurn.class);
        final SupervisorCapabilityCatalog catalog = mock(SupervisorCapabilityCatalog.class);
        final SkillRegistry skills = mock(SkillRegistry.class);
        final SupervisorOrchestrationProperties orchestration = new SupervisorOrchestrationProperties();
        final AtomicInteger effects = new AtomicInteger();
        final AtomicInteger summaryCalls = new AtomicInteger();
        final List<List<Message>> inputs = new ArrayList<>();
        final SessionStreamService stream = mock(SessionStreamService.class);
        Runnable onToolCall = () -> { };
        Runnable onSummary = () -> { };
        String toolResult = "{\"result\":\"complete structured fact\",\"number\":123}";
        SupervisorMemoryJournal journal;
        final Remote remote = new Remote();
        final SupervisorToolLoopGraph factory;
        final SupervisorToolLoopRunner runner;
        final com.alibaba.cloud.ai.graph.CompiledGraph graph;
        SupervisorMemoryLifecycle lifecycle;
        AgentRagCredentialLeaseStore identities;

        @SuppressWarnings("unchecked") Fixture() throws Exception {
            Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory"));
            String bootstrap = Files.readString(root.resolve("sql/mysql-init.sql"));
            int start = bootstrap.indexOf("CREATE TABLE IF NOT EXISTS agent_workflow_checkpoint (");
            int end = bootstrap.indexOf(";", start);
            String checkpointDdl = bootstrap.substring(start, end + 1).replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
            try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(checkpointDdl.getBytes(StandardCharsets.UTF_8)));
                String migration = Files.readString(root.resolve("sql/migrations/20261019_supervisor_memory_delivery.sql"))
                        .replace("USE bk_agent;", "").replace(" CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
            }
            properties.setEnabled(true); properties.setKeyId("agent-service-current");
            properties.setPrivateKeyFile(privateKeyFile(remote.identityPair).toString());
            properties.setSoftTokens(10000); properties.setHardTokens(20000);
            var persistence = mock(AgentWorkflowCheckpointMapper.class);
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "memory-fixture"), AgentWorkflowCheckpointEntity.class);
            when(persistence.selectOne(any())).thenAnswer(invocation -> rows(task(invocation.getArgument(0))).stream().findFirst().orElse(null));
            when(persistence.selectList(any())).thenAnswer(invocation -> rows(task(invocation.getArgument(0))));
            when(persistence.insert(any(AgentWorkflowCheckpointEntity.class))).thenAnswer(invocation -> {
                AgentWorkflowCheckpointEntity row = invocation.getArgument(0);
                return jdbc.update("INSERT INTO agent_workflow_checkpoint(task_id,session_id,trace_id,workflow_status,snapshot_json,checkpoint_version) VALUES(?,?,?,?,?,?)",
                        row.getTaskId(), row.getSessionId(), row.getTraceId(), row.getWorkflowStatus(), row.getSnapshotJson(), row.getCheckpointVersion());
            });
            when(skills.findOperationalSkills("supervisor")).thenReturn(List.of()); when(skills.findSupervisorSkills()).thenReturn(List.of());
            ToolCallback tool = new ToolCallback() {
                public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("find").description("local read").inputSchema("{\"type\":\"object\"}").build(); }
                public String call(String arguments) { effects.incrementAndGet(); onToolCall.run(); return toolResult; }
            };
            when(catalog.snapshot("1", true)).thenReturn(Map.of("local:find", new SupervisorCapability("local:find", "local", "find", "1", tool)));
            factory = new SupervisorToolLoopGraph(catalog, model, skills, store, mapper, orchestration, stream,
                    new OfficialSupervisorGraphSchema(), new DatabaseCheckpointSaverFactory(persistence, mapper));
            graph = factory.create();
            runner = new SupervisorToolLoopRunner(factory, graph, store, orchestration, mock(ApprovalResumeClaimStore.class));
            restartIdentity();
        }
        void restartIdentity() {
            if (lifecycle != null) lifecycle.close();
            var auth = mock(AuthPermissionRpcService.class); when(auth.resolvePrincipal("transport-only-value")).thenReturn(new AuthenticatedPrincipal(1L));
            var beans = new DefaultListableBeanFactory(); beans.registerSingleton("fixtureAuth", auth);
            identities = new AgentRagCredentialLeaseStore(new AgentRagConsumerProperties(), beans.getBeanProvider(AuthPermissionRpcService.class));
            var reference = mock(ReferenceConfig.class); when(reference.get()).thenReturn(remote.rpc);
            journal = spy(new SupervisorMemoryJournal(jdbc, mapper, new DataSourceTransactionManager(jdbc.getDataSource())));
            lifecycle = new SupervisorMemoryLifecycle(properties, journal,
                    store, new MemoryWorkingSetCache(8, 8 * 1024 * 1024, 60000, Clock.systemUTC()), reference, identities, mapper, model);
            factory.setRagCredentials(identities); runner.setRagCredentials(identities); factory.setMemory(lifecycle);
        }
        void localToolThenAnswer() {
            when(model.call(anyList(), anyList())).thenAnswer(invocation -> {
                List<ToolCallback> tools = invocation.getArgument(1);
                if (tools.isEmpty()) { summaryCalls.incrementAndGet(); onSummary.run(); return new AssistantMessage("{\"items\":[{\"kind\":\"FACT\",\"text\":\"summarized input\",\"evidence\":[0]}],\"entities\":[]}"); }
                inputs.add(invocation.getArgument(0));
                if (effects.get() == 0) return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "find", "{}"))).build();
                return new AssistantMessage("done");
            });
        }
        SupervisorTaskRequest request(String run, String input) { return new SupervisorTaskRequest("session", "1", run, "trace", input, Map.of(), "api", false); }
        com.bkanent.agent.model.distributed.SupervisorTaskResponse execute(String run, String input) throws Exception {
            try (var scope = identities.openAuthorization("Bearer transport-only-value")) { return runner.execute(request(run, input)); }
        }
        com.bkanent.agent.model.distributed.SupervisorTaskResponse execute(String run, String input, MemoryMatterIntent intent) throws Exception {
            var request = new SupervisorTaskRequest("session", "1", run, "trace", input, Map.of(), "api", false, null, null, null, intent);
            try (var scope = identities.openAuthorization("Bearer transport-only-value")) { return runner.execute(request); }
        }
        ToolLoopState state(String run) { return factory.stateOf(graph.lastStateOf(com.alibaba.cloud.ai.graph.RunnableConfig.builder().threadId(run).build()).orElseThrow().state()); }
        String task(LambdaQueryWrapper<?> query) { query.getSqlSegment(); return (String) query.getParamNameValuePairs().values().iterator().next(); }
        List<AgentWorkflowCheckpointEntity> rows(String run) { return jdbc.query("SELECT * FROM agent_workflow_checkpoint WHERE task_id=? ORDER BY checkpoint_version DESC", (rs, n) -> {
            var row = new AgentWorkflowCheckpointEntity(); row.setTaskId(run); row.setCheckpointVersion(rs.getInt("checkpoint_version")); row.setSnapshotJson(rs.getString("snapshot_json")); return row;
        }, run); }
        public void close() { lifecycle.close(); factory.close(); runner.close(); }
    }

    static class Remote {
        final java.security.KeyPair identityPair = generateIdentityPair();
        final Map<String,Integer> calls = new HashMap<>();
        final Map<String,List<MemoryReceivePermitRequest.EventSubmission>> permits = new HashMap<>();
        MemoryWorkingSetResponse snapshot = new MemoryWorkingSetResponse(new MemoryContextKey("1", "supervisor", "supervisor-agent", "session"),
                new MemoryConversationVersion(0, 0, "fixture-v2"), List.of(), List.of(), List.of());
        long fence;
        int activeWriters;
        boolean loseToolReceipt;
        boolean revoked;
        boolean unavailable;
        boolean preferenceUnavailable;
        String preferenceValue;
        long preferenceRevision = 1;
        boolean experienceUnavailable;
        String experienceValue;
        long experienceRevision = 1;
        final Map<String, MemoryMatterWriteResponse> matterReceipts = new HashMap<>();
        final MemoryV2RpcService rpc = (MemoryV2RpcService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{MemoryV2RpcService.class}, (proxy, method, args) -> {
            String name = method.getName();
            calls.merge(name, 1, Integer::sum);
            if (unavailable) throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
            MemoryV2Request request = (MemoryV2Request) args[0];
            MemoryRequestProof proof = (MemoryRequestProof) args[1];
            MemoryRequestSigner.verifyEd25519(request, request.contextKey(), proof, "agent-service", MemoryV2Contract.MEMORY_AUDIENCE,
                    request.envelope().operation(), "agent-service-current", Instant.now(), identityPair.getPublic());
            if (!name.equals("deliverMessages") && proof.actor() != MemoryRequestProof.Actor.USER) throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
            return switch (name) {
                case "manageWriter" -> {
                    var writer = (MemoryWriterRequest) request;
                    if (writer.action() == MemoryWriterRequest.Action.ACQUIRE) { activeWriters = 1; fence++; }
                    else if (writer.fence() != fence) throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
                    if (writer.action() == MemoryWriterRequest.Action.RELEASE) activeWriters = 0;
                    yield new MemoryWriterResponse(new MemoryWriteAuthority(writer.runId(), fence), System.currentTimeMillis() + 120000, snapshot.version());
                }
                case "readWorkingSet" -> snapshot;
                case "queryEntities" -> new MemoryEntityQueryResponse(List.of(), null, false);
                case "assemblePreferences" -> {
                    if (preferenceUnavailable) throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
                    var assembly = (MemoryPreferenceAssemblyRequest) request;
                    var matter = snapshot.matters().stream().filter(value -> value.matterId().equals(assembly.matterId())).findFirst().orElseThrow();
                    Instant asOf = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                    List<MemoryPreferenceMatch> matches = List.of();
                    if (preferenceValue != null) {
                        var evidence = matter.dependencies().get(0);
                        var state = new PreferenceCurrentState(PreferenceCurrentState.Status.EFFECTIVE, MemoryJsonValue.string(preferenceValue),
                                List.of("transition-fixture"), List.of(), List.of(), .8, 1.0, asOf, List.of(evidence));
                        var selection = new MemoryPreferenceSelectionRef("p-fixture", preferenceRevision, "1".repeat(64), 1,
                                matter.matterId(), matter.version().revision(), MemoryCanonicalHash.contentHash(state));
                        var score = new MemorySelectionScore(.9, .8, 1.0, 1.0, asOf, "preference-selection-v1");
                        matches = List.of(new MemoryPreferenceMatch("budget", state, score, selection));
                    }
                    yield new MemoryPreferenceAssemblyResponse(matter.matterId(), matter.version().revision(),
                            MemoryCanonicalHash.contentHash(matter.goal()), asOf, "preference-selection-v1", matches, false);
                }
                case "searchExperiences" -> {
                    if (experienceUnavailable) throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
                    var query = (MemoryExperienceQueryRequest) request;
                    var matter = snapshot.matters().stream().filter(value -> value.matterId().equals(query.matterId())).findFirst().orElseThrow();
                    Instant asOf = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                    List<MemoryExperienceMatch> matches = experienceValue == null ? List.of() : List.of(experienceMatch(matter, asOf));
                    yield new MemoryExperienceQueryResponse(matches, null, false, "experience-selection-v1", asOf,
                            matter.matterId(), matter.version().revision(), "3".repeat(64), matches.stream().mapToInt(MemoryExperienceMatch::estimatedTokens).sum(), false);
                }
                case "transitionMatter" -> {
                    var write = (MemoryMatterWriteRequest) request;
                    String event = write.evidence().source().sourceEventId();
                    if (matterReceipts.containsKey(event)) yield matterReceipts.get(event);
                    String id = write.intent().action() == MemoryMatterIntent.Action.START ? "matter-" + MemoryCanonicalHash.contentHash(List.of(write.contextKey(), event)) : write.intent().matterId();
                    var old = snapshot.matters().stream().filter(m -> id.equals(m.matterId())).findFirst().orElse(null);
                    var dependencies = new ArrayList<MemorySourceDependency>(old == null ? List.of() : old.dependencies()); dependencies.add(write.evidence());
                    var state = write.intent().action() == MemoryMatterIntent.Action.CLOSE ? MemoryMatter.State.CLOSED : write.intent().action() == MemoryMatterIntent.Action.REOPEN ? MemoryMatter.State.REOPENED : MemoryMatter.State.ACTIVE;
                    var closure = state == MemoryMatter.State.CLOSED ? new MemoryMatter.ClosureEvidence(MemoryMatter.ClosureKind.USER_CONFIRMATION, List.of(write.evidence())) : old == null ? null : old.closure();
                    var draft = new MemoryMatter(id, write.contextKey(), new MemoryRevision(old == null ? 1 : old.version().revision() + 1, "0".repeat(64), "matter.v2.1"), old == null ? write.intent().goal() : old.goal(), state, closure, dependencies);
                    var matter = new MemoryMatter(id, draft.contextKey(), new MemoryRevision(draft.version().revision(), MemoryCanonicalHash.matterHash(draft), "matter.v2.1"), draft.goal(), state, closure, dependencies);
                    var matters = new ArrayList<>(snapshot.matters().stream().filter(m -> !id.equals(m.matterId())).toList()); matters.add(matter);
                    snapshot = new MemoryWorkingSetResponse(snapshot.contextKey(), new MemoryConversationVersion(snapshot.version().revision() + 1, snapshot.version().lastSequence(), "fixture-v2"), snapshot.messages(), snapshot.summaries(), matters);
                    var response = new MemoryMatterWriteResponse(matter, snapshot.version(), true); matterReceipts.put(event, response); yield response;
                }
                case "checkBoundary" -> {
                    if (revoked) throw new MemoryException(MemoryErrorCode.WITHDRAWN);
                    var boundary = (MemoryBoundaryRequest) request;
                    if (boundary.preferenceSelections().stream().anyMatch(selection -> selection.revision() != preferenceRevision))
                        throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
                    if (boundary.experienceSelections().stream().anyMatch(selection -> selection.experienceVersion().revision() != experienceRevision))
                        throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
                    yield new MemoryBoundaryResponse(snapshot.version(), snapshot.version().equals(boundary.adoptedVersion()));
                }
                case "requestReceivePermit" -> {
                    var permit = (MemoryReceivePermitRequest) request; String id = UUID.randomUUID().toString(); permits.put(id, permit.events());
                    yield new MemoryReceivePermitResponse(id, permit.events().stream().map(event -> new MemoryReceivePermitResponse.EventAcceptance(event.eventId(), true, null)).toList());
                }
                case "deliverMessages" -> {
                    var delivery = (MemoryDeliveryRequest) request;
                    List<MemoryMessage> messages = new ArrayList<>(snapshot.messages()); boolean changed = false;
                    long last = snapshot.version().lastSequence();
                    for (var message : delivery.messages()) {
                        if (messages.stream().anyMatch(m -> m.source().sourceEventId().equals(message.source().sourceEventId()))) continue;
                        if (message.sequence() != last + 1) throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
                        messages.add(message); last++; changed = true;
                    }
                    snapshot = new MemoryWorkingSetResponse(snapshot.contextKey(), new MemoryConversationVersion(snapshot.version().revision() + (changed ? 1 : 0), last, "fixture-v2"), messages, snapshot.summaries(), snapshot.matters());
                    if (loseToolReceipt && delivery.messages().stream().anyMatch(m -> m.role() == MemoryMessage.Role.TOOL)) throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
                    yield new MemoryDeliveryResponse(delivery.permitId(), snapshot.version(), delivery.messages().stream().map(m -> new MemoryDeliveryResponse.DeliveryResult(m.sequence(), true, null)).toList());
                }
                case "submitSummary" -> {
                    var summary = ((MemorySummaryRequest) request).summary();
                    if (summary.expectedConversationRevision() != snapshot.version().revision()) throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
                    snapshot = new MemoryWorkingSetResponse(snapshot.contextKey(), new MemoryConversationVersion(snapshot.version().revision() + 1, snapshot.version().lastSequence(), "fixture-v2"),
                            snapshot.messages().stream().filter(m -> m.sequence() > summary.coveredRange().throughSequence()).toList(), List.of(summary), snapshot.matters());
                    yield new MemorySummaryResponse(summary.summaryId(), true, null, snapshot.version());
                }
                default -> throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
            };
        });
        int count(String method) { return calls.getOrDefault(method, 0); }

        private MemoryExperienceMatch experienceMatch(MemoryMatter matter, Instant asOf) {
            var dependency = matter.dependencies().get(0);
            var evidence = new MemoryExperience.Evidence("experience-evidence", MemoryExperience.EvidenceKind.BUSINESS_OUTCOME,
                    MemoryExperience.Outcome.SUCCEEDED, MemoryExperience.Feedback.NONE, true, asOf, asOf, List.of(dependency));
            var context = matter.contextKey();
            var experience = new MemoryExperience("experience-fixture", context,
                    new MemoryAssociation(matter.matterId(), null, null, null),
                    new MemoryRevision(experienceRevision, "2".repeat(64), "experience.v1"), "买房", "预算相关", experienceValue,
                    MemoryState.ACTIVE, "fixture", null, .8, 1.0, asOf, asOf, "experience-assessment-v1", List.of(evidence));
            var score = new MemoryExperienceScore(.8, .8, 1.0, 1.0, .64, asOf, "experience-selection-v1");
            return new MemoryExperienceMatch(experience, score, 24);
        }
    }
}
