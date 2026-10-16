package com.bkanent.a2a.memory;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Actual dependency contract probes, not a production memory implementation.
 * Unsafe native behavior is intentionally asserted as evidence, never as acceptance.
 * Coverage, summary commit, authorization and revision below are in-process fixtures;
 * they do not establish a database transaction, lease or run/checkpoint CAS.
 */
class SummarizationHookContractTest {

    private static final RunnableConfig CONFIG = RunnableConfig.builder()
            .addMetadata("_stream_", false).build();
    private static final String VALID_SUMMARY = "Grounded fixture fact; source=message-1; unresolved=false";

    @Test
    void resolvedRuntimeArtifactsHaveTheExpectedCoordinatesAndHashes() throws Exception {
        assertArtifact(SummarizationHook.class, "com.alibaba.cloud.ai", "spring-ai-alibaba-agent-framework", "1.1.2.3",
                "2cc840e12070f2dffd015a0f2f0df803f46e7ebc83ac3d4f4c36b79c1dd03739");
        assertArtifact(OverAllState.class, "com.alibaba.cloud.ai", "spring-ai-alibaba-graph-core", "1.1.2.3",
                "a74b180d531fdd5191538466f3720bc11109e7e19d9c6219cb7d8d40dd5ab4f2");
        assertArtifact(ToolResponseMessage.class, "org.springframework.ai", "spring-ai-model", "1.1.2",
                "694d972dea31846bbb0b1f0a50cb72309681c3396a8e520da213323779a1406c");
        assertArtifact(org.springframework.ai.chat.client.ChatClient.class, "org.springframework.ai", "spring-ai-client-chat", "1.1.2",
                "79661d5aacfdff6031bfc0c6eeb877feb471338483e0c996f6b24c74d5506fc7");
    }

    @Test
    void publicActionBridgeExposesReplacementWithoutMutatingOriginalState() {
        List<Message> original = plainWindow();
        OverAllState state = state(original);
        SummarizationHook hook = nativeHook(summaryModel(VALID_SUMMARY), 1, 1, messages -> 1);
        assertThat(hook.getHookPositions()).containsExactly(HookPosition.BEFORE_MODEL);
        assertThat(hook.getOrder()).isZero();
        Map<String, Object> update = nativeUpdate(hook, state);
        assertThat(update.get("messages")).isInstanceOf(ReplaceAllWith.class);
        assertThat(replacement(update)).hasSize(2);
        assertThat(window(state)).containsExactlyElementsOf(original);
        assertThat(original).hasSize(3);
        Object replaced = new AppendStrategy().apply(original, update.get("messages"));
        assertThat(replaced).isEqualTo(replacement(update)).isNotSameAs(original);
        assertThat(original).hasSize(3);
    }

    @Test
    void packagePrivateCommandAccessorsDoNotBlockThePublicActionBridge() throws Exception {
        assertThat(Modifier.isPublic(AgentCommand.class.getDeclaredMethod("getMessages").getModifiers())).isFalse();
        assertThat(Modifier.isPublic(AgentCommand.class.getDeclaredMethod("getUpdatePolicy").getModifiers())).isFalse();
        // No reflective access, setAccessible, cache patching or same-framework-package shim is used.
        assertThat(nativeUpdate(nativeHook(summaryModel(VALID_SUMMARY), 1, 1, messages -> 1),
                state(plainWindow()))).containsKey("messages");
    }

    @Test
    void thresholdIsInclusiveAndTheCounterIsReplaceable() {
        AtomicInteger estimate = new AtomicInteger(9);
        AtomicInteger counterCalls = new AtomicInteger();
        ChatModel model = summaryModel(VALID_SUMMARY);
        SummarizationHook hook = nativeHook(model, 10, 1, messages -> {
            counterCalls.incrementAndGet();
            return estimate.get();
        });
        List<Message> original = plainWindow();
        assertThat(replacement(nativeUpdate(hook, state(original)))).containsExactlyElementsOf(original);
        verifyNoInteractions(model);
        estimate.set(10);
        assertThat(replacement(nativeUpdate(hook, state(original)))).hasSize(2);
        assertThat(counterCalls).hasValue(2);
    }

    @Test
    void disabledThresholdDoesNotEvenEstimateAndSmallWindowDoesNotGenerate() {
        ChatModel model = summaryModel(VALID_SUMMARY);
        SummarizationHook disabled = nativeHook(model, null, 1, messages -> {
            throw new AssertionError("disabled estimator must not run");
        });
        assertThat(replacement(nativeUpdate(disabled, state(plainWindow())))).hasSize(3);
        assertThat(replacement(nativeUpdate(nativeHook(model, 1, 3, messages -> 1), state(plainWindow())))).hasSize(3);
        verifyNoInteractions(model);
        // Native no-op still produces ReplaceAllWith(original), not an empty update.
    }

    @Test
    void approximateCounterFloorsEachPartAndCountsArgumentsAndFullResponseDataNotMetadata() {
        TokenCounter counter = TokenCounter.approximateMsgCounter();
        Message call = AssistantMessage.builder().content("abcd")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "fixtureTool", "12345678")))
                .properties(Map.of("ignored", "x".repeat(1000))).build();
        Message result = response("call-1", "123456789012");
        assertThat(counter.countTokens(List.of(new UserMessage("中文测试abcd"), call, result))).isEqualTo(8);
        assertThat(counter.countTokens(List.of(new UserMessage("abc"), new UserMessage("abc")))).isZero();
        assertThat(TokenCounter.approximateMsgCounter(2).countTokens(List.of(result))).isEqualTo(6);
        assertThat(result.getText()).isEmpty();
        assertThat(counter.countTokens(List.of(response("call-1", "x".repeat(10000))))).isEqualTo(2500);
    }

    @Test
    void estimatorFailuresPropagateBeforeAnyUpdateUnlikeModelFailures() {
        ChatModel model = summaryModel(VALID_SUMMARY);
        OverAllState state = state(plainWindow());
        SummarizationHook hook = nativeHook(model, 1, 1, messages -> {
            throw new IllegalStateException("fixture-counter-failure");
        });
        assertThatThrownBy(() -> nativeUpdate(hook, state)).isInstanceOf(IllegalStateException.class)
                .hasMessage("fixture-counter-failure");
        assertThat(window(state)).containsExactlyElementsOf(plainWindow());
        verifyNoInteractions(model);
    }

    @Test
    void nativeModelExceptionIsSwallowedAndBecomesUnsafeSystemReplacement() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("fixture-summary-unavailable"));
        OverAllState state = state(plainWindow());
        Map<String, Object> update = nativeUpdate(nativeHook(model, 1, 1, messages -> 1), state);
        assertThat(replacement(update).get(0)).isInstanceOf(SystemMessage.class);
        assertThat(replacement(update).get(0).getText())
                .contains("Summary generation failed: fixture-summary-unavailable");
        assertThat(window(state)).hasSize(3);
        apply(state, update);
        assertThat(window(state)).hasSize(2);
        assertThat(window(state).get(0).getText()).contains("Summary generation failed:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Summary generation failed: fixture", "unsupported fixture statement"})
    void gateRejectsEmptyFailedAndUngroundedNativeCandidatesWithoutReplacement(String summary) {
        GuardFixture guard = guard(summaryModel(summary), true, () -> true);
        OverAllState state = state(plainWindow());
        Map<String, Object> update = guard.beforeModel(state, CONFIG).join();
        assertThat(update).isEmpty();
        apply(state, update);
        assertThat(window(state)).containsExactlyElementsOf(plainWindow());
        assertThat(guard.commitCalls).hasValue(0);
    }

    @Test
    void gateRejectsActualNativeExceptionCandidateAndLeavesWindowIntact() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("fixture-summary-unavailable"));
        GuardFixture guard = guard(model, true, () -> true);
        OverAllState state = state(plainWindow());
        Map<String, Object> update = guard.beforeModel(state, CONFIG).join();
        assertThat(update).isEmpty();
        apply(state, update);
        assertThat(window(state)).containsExactlyElementsOf(plainWindow());
        assertThat(guard.commitCalls).hasValue(0);
    }

    @Test
    void coverageNotConfirmedPreventsGenerationCommitAndReplacement() {
        ChatModel model = summaryModel(VALID_SUMMARY);
        GuardFixture guard = guard(model, false, () -> true);
        OverAllState state = state(plainWindow());
        assertThat(guard.beforeModel(state, CONFIG).join()).isEmpty();
        assertThat(window(state)).containsExactlyElementsOf(plainWindow());
        verifyNoInteractions(model);
        assertThat(guard.commitCalls).hasValue(0);
    }

    @Test
    void revisionChangeDuringGenerationRejectsCandidateAndPreservesNewInput() {
        AtomicLong revision = new AtomicLong(7);
        OverAllState state = state(plainWindow());
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            apply(state, Map.of("messages", List.of(new UserMessage("new input"))));
            revision.incrementAndGet();
            return chatResponse(VALID_SUMMARY);
        });
        GuardFixture guard = new GuardFixture(nativeHook(model, 1, 1, messages -> 1), revision, true, () -> true);
        assertThat(guard.beforeModel(state, CONFIG).join()).isEmpty();
        assertThat(window(state)).hasSize(4);
        assertThat(window(state).get(3).getText()).isEqualTo("new input");
        assertThat(guard.commitCalls).hasValue(0);
    }

    @Test
    void failedSummaryCommitOrCommitExceptionCannotEmitReplacement() {
        for (BooleanSupplier commit : List.<BooleanSupplier>of(() -> false, () -> {
            throw new IllegalStateException("fixture-commit-unavailable");
        })) {
            GuardFixture guard = guard(summaryModel(VALID_SUMMARY), true, commit);
            OverAllState state = state(plainWindow());
            assertThat(guard.beforeModel(state, CONFIG).join()).isEmpty();
            assertThat(window(state)).containsExactlyElementsOf(plainWindow());
            assertThat(guard.commitCalls).hasValue(1);
        }
    }

    @Test
    void revisionChangeInsideSuccessfulCommitStillPreventsWindowReplacement() {
        AtomicLong revision = new AtomicLong(7);
        GuardFixture guard = new GuardFixture(nativeHook(summaryModel(VALID_SUMMARY), 1, 1, messages -> 1),
                revision, true, () -> {
                    revision.incrementAndGet();
                    return true;
                });
        OverAllState state = state(plainWindow());
        assertThat(guard.beforeModel(state, CONFIG).join()).isEmpty();
        assertThat(window(state)).containsExactlyElementsOf(plainWindow());
        assertThat(guard.commitCalls).hasValue(1);
        // Summary commit success and runtime adoption are different facts.
    }

    @Test
    void onlyValidatedCandidateAfterCoverageAndSuccessfulCommitCanReachReplacement() {
        List<String> events = new ArrayList<>();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            events.add("summary");
            return chatResponse(VALID_SUMMARY);
        });
        GuardFixture guard = guard(model, true, () -> {
            events.add("durable-summary-commit");
            return true;
        });
        OverAllState state = state(plainWindow());
        Map<String, Object> update = guard.beforeModel(state, CONFIG).join();
        assertThat(update.get("messages")).isInstanceOf(VerifiedReplacement.class);
        assertThat(window(state)).containsExactlyElementsOf(plainWindow());
        state.registerKeyAndStrategy(guard.getKeyStrategys());
        apply(state, update);
        assertThat(window(state)).hasSize(2);
        assertThat(events).containsExactly("summary", "durable-summary-commit");
        assertThat(guard.commitCalls).hasValue(1);
        assertThat(plainWindow()).hasSize(3);
    }

    @Test
    void plainAppendStrategyCannotProtectRevisionChangeAfterAGateReturns() {
        OverAllState state = state(plainWindow());
        Map<String, Object> candidate = nativeUpdate(nativeHook(summaryModel(VALID_SUMMARY), 1, 1, messages -> 1), state);
        apply(state, Map.of("messages", List.of(new UserMessage("new input after candidate"))));
        apply(state, candidate);
        assertThat(window(state)).hasSize(2);
        assertThat(window(state)).noneMatch(message -> "new input after candidate".equals(message.getText()));
        // A check-before-return plus ordinary REPLACE is NOT a run-state CAS.
    }

    @Test
    void publicHookKeyStrategyCanRejectLateCandidateAtActualUpdateBoundary() {
        GuardFixture guard = guard(summaryModel(VALID_SUMMARY), true, () -> true);
        OverAllState state = state(plainWindow());
        state.registerKeyAndStrategy(guard.getKeyStrategys());
        Map<String, Object> candidate = guard.beforeModel(state, CONFIG).join();
        apply(state, Map.of("messages", List.of(new UserMessage("new input after candidate"))));
        guard.revision.incrementAndGet();
        apply(state, candidate);
        assertThat(window(state)).hasSize(4);
        assertThat(window(state).get(3).getText()).isEqualTo("new input after candidate");
        assertThat(guard.commitCalls).hasValue(1);
    }

    @Test
    void nativeCutoffProtectsNearbyPairsButSplitsPairsBeyondFiveMessageNeighborhood() {
        AssistantMessage call = call("long-call");
        ToolResponseMessage result = response("long-call", "{\"status\":\"OUTCOME_UNKNOWN\"}");
        List<Message> nearby = List.of(new UserMessage("old"), call, result, new UserMessage("tail"));
        List<Message> nearbyCandidate = replacement(nativeUpdate(nativeHook(summaryModel(VALID_SUMMARY), 1, 2,
                messages -> 1), state(nearby)));
        assertThat(nearbyCandidate).contains(call, result);
        List<Message> distant = new ArrayList<>();
        distant.add(call);
        for (int i = 0; i < 8; i++) distant.add(new UserMessage("intervening-" + i));
        distant.add(result);
        distant.add(new UserMessage("tail"));
        List<Message> distantCandidate = replacement(nativeUpdate(nativeHook(summaryModel(VALID_SUMMARY), 1, 2,
                messages -> 1), state(distant)));
        assertThat(distantCandidate).contains(result).doesNotContain(call);
        assertThat(completeToolGroups(distant)).isTrue();
        assertThat(completeToolGroups(distantCandidate)).isFalse();
        GuardFixture guard = guard(summaryModel(VALID_SUMMARY), true, () -> true);
        OverAllState original = state(distant);
        assertThat(guard.beforeModel(original, CONFIG).join()).isEmpty();
        assertThat(window(original)).containsExactlyElementsOf(distant);
    }

    @Test
    void nativeCutoffDoesNotRequireAllParallelResponsesToExist() {
        AssistantMessage calls = AssistantMessage.builder().content("parallel")
                .toolCalls(List.of(new AssistantMessage.ToolCall("a", "function", "fixtureTool", "{}"),
                        new AssistantMessage.ToolCall("b", "function", "fixtureTool", "{}"))).build();
        List<Message> pending = List.of(calls, response("a", "{\"status\":\"SUCCESS\"}"),
                new UserMessage("tail"));
        List<Message> candidate = replacement(nativeUpdate(nativeHook(summaryModel(VALID_SUMMARY), 1, 1,
                messages -> 1), state(pending)));
        assertThat(candidate).doesNotContain(calls);
        assertThat(completeToolGroups(pending)).isFalse();
        GuardFixture guard = guard(summaryModel(VALID_SUMMARY), true, () -> true);
        assertThat(guard.beforeModel(state(pending), CONFIG).join()).isEmpty();
        assertThat(guard.commitCalls).hasValue(0);
    }

    @Test
    void nativeSummaryPromptLosesAllStructuredResponseDataAndToolCallIdentity() {
        AtomicReference<Prompt> seen = new AtomicReference<>();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            seen.set(invocation.getArgument(0));
            return chatResponse(VALID_SUMMARY);
        });
        String data = "{\"summary\":\"brief\",\"status\":\"OUTCOME_UNKNOWN\",\"sourceVersion\":3,"
                + "\"facts\":[\"only-in-response-data-" + "x".repeat(10000) + "\"]}";
        List<Message> original = List.of(new UserMessage("old"), call("source-call"),
                response("source-call", data), new UserMessage("tail"));
        nativeUpdate(nativeHook(model, 1, 1, messages -> 1), state(original));
        assertThat(seen.get().getContents()).contains("Tool: \n")
                .doesNotContain("OUTCOME_UNKNOWN", "sourceVersion", "only-in-response-data", "source-call", "fixtureTool");
        assertThat(((ToolResponseMessage) original.get(2)).getResponses().get(0).responseData()).isEqualTo(data);
        GuardFixture guard = guard(model, true, () -> true);
        assertThat(guard.beforeModel(state(original), CONFIG).join()).isEmpty();
        assertThat(guard.commitCalls).hasValue(0);
        // This conservative native-input fixture refuses to compact covered tools, rather than claiming lossless serialization.
    }

    @Test
    void firstUserInRetainedTailIsNativelyDuplicatedButGuardRejectsThatCandidate() {
        UserMessage first = new UserMessage("first user in tail");
        List<Message> original = List.of(new SystemMessage("old"), new AssistantMessage("old answer"), first);
        SummarizationHook nativeHook = SummarizationHook.builder().model(summaryModel(VALID_SUMMARY))
                .maxTokensBeforeSummary(1).messagesToKeep(1).tokenCounter(messages -> 1).build();
        List<Message> candidate = replacement(nativeUpdate(nativeHook, state(original)));
        assertThat(candidate).containsExactly(first, candidate.get(1), first);
        GuardFixture guard = new GuardFixture(nativeHook, new AtomicLong(7), true, () -> true);
        OverAllState state = state(original);
        assertThat(guard.beforeModel(state, CONFIG).join()).isEmpty();
        assertThat(window(state)).containsExactlyElementsOf(original);
    }

    @Test
    void realReactAgentRunsBeforeHooksInOrderThenSummaryCommitModelAndReverseAfterHooks() throws Exception {
        List<String> events = new ArrayList<>();
        ChatModel summary = mock(ChatModel.class);
        when(summary.call(any(Prompt.class))).thenAnswer(invocation -> {
            events.add("summary");
            return chatResponse(VALID_SUMMARY);
        });
        GuardFixture guard = guard(summary, true, () -> {
            events.add("durable-summary-commit");
            return true;
        });
        AtomicReference<Prompt> seen = new AtomicReference<>();
        ChatModel main = mock(ChatModel.class);
        when(main.call(any(Prompt.class))).thenAnswer(invocation -> {
            events.add("model");
            seen.set(invocation.getArgument(0));
            return chatResponse("done");
        });
        ReactAgent agent = ReactAgent.builder().name("fixture-agent").model(main).enableLogging(false)
                .hooks(trace("late", 10, events), guard, trace("early", -10, events)).build();
        assertThat(agent.call(plainWindow(), CONFIG).getText()).isEqualTo("done");
        assertThat(events).containsExactly("early.before", "summary", "durable-summary-commit",
                "late.before", "model", "late.after", "early.after");
        assertThat(seen.get().getContents()).contains(VALID_SUMMARY).doesNotContain("old input", "old answer");
        assertThat(guard.commitCalls).hasValue(1);
    }

    @Test
    void realReactAgentWithRejectedGuardSendsOriginalWindowNotFailedSummary() throws Exception {
        ChatModel summary = mock(ChatModel.class);
        when(summary.call(any(Prompt.class))).thenThrow(new IllegalStateException("fixture-summary-unavailable"));
        GuardFixture guard = guard(summary, true, () -> true);
        AtomicReference<Prompt> seen = new AtomicReference<>();
        ChatModel main = mock(ChatModel.class);
        when(main.call(any(Prompt.class))).thenAnswer(invocation -> {
            seen.set(invocation.getArgument(0));
            return chatResponse("done");
        });
        ReactAgent agent = ReactAgent.builder().name("fixture-agent").model(main).enableLogging(false).hooks(guard).build();
        assertThat(agent.call(plainWindow(), CONFIG).getText()).isEqualTo("done");
        assertThat(seen.get().getContents()).contains("old input", "old answer", "tail")
                .doesNotContain("Summary generation failed:");
        assertThat(guard.commitCalls).hasValue(0);
    }

    private static ModelHook trace(String name, int order, List<String> events) {
        return new ModelHook() {
            @Override public String getName() { return name; }
            @Override public int getOrder() { return order; }
            @Override public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
                events.add(name + ".before");
                return CompletableFuture.completedFuture(Map.of());
            }
            @Override public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
                events.add(name + ".after");
                return CompletableFuture.completedFuture(Map.of());
            }
        };
    }

    private static GuardFixture guard(ChatModel model, boolean durable, BooleanSupplier commit) {
        return new GuardFixture(nativeHook(model, 1, 1, messages -> 1), new AtomicLong(7), durable, commit);
    }

    /** Only public framework extension points; intentionally conservative for unprojected tool data. */
    private static final class GuardFixture extends ModelHook {
        private final SummarizationHook delegate;
        private final AtomicLong revision;
        private final AtomicBoolean durable;
        private final BooleanSupplier commit;
        private final AtomicInteger commitCalls = new AtomicInteger();

        private GuardFixture(SummarizationHook delegate, AtomicLong revision, boolean durable, BooleanSupplier commit) {
            this.delegate = delegate;
            this.revision = revision;
            this.durable = new AtomicBoolean(durable);
            this.commit = commit;
        }

        @Override public String getName() { return "fixture-guarded-summarization"; }
        @Override public HookPosition[] getHookPositions() { return new HookPosition[]{HookPosition.BEFORE_MODEL}; }

        @Override
        public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
            long expected = revision.get();
            List<Message> frozen = List.copyOf(window(state));
            if (!durable.get() || !completeToolGroups(frozen)) return CompletableFuture.completedFuture(Map.of());
            // The native bridge returns an inspectable update, but does NOT apply it to the original state.
            Map<String, Object> candidateUpdate = MessagesModelHook.beforeModelAction(delegate)
                    .apply(SummarizationHookContractTest.state(frozen), config).join();
            List<Message> candidate = replacement(candidateUpdate);
            int cutoff = cutoff(frozen, candidate);
            boolean valid = cutoff > 0 && candidate.get(0) instanceof SystemMessage
                    && candidate.get(0).getText().equals("## Previous conversation summary:\n" + VALID_SUMMARY)
                    && completeToolGroups(candidate)
                    && frozen.subList(0, cutoff).stream().noneMatch(message -> message instanceof ToolResponseMessage
                            || message instanceof AssistantMessage assistant && assistant.hasToolCalls());
            if (!valid || revision.get() != expected || !window(state).equals(frozen)) {
                return CompletableFuture.completedFuture(Map.of());
            }
            try {
                commitCalls.incrementAndGet();
                if (!commit.getAsBoolean() || !durable.get() || revision.get() != expected || !window(state).equals(frozen)) {
                    return CompletableFuture.completedFuture(Map.of());
                }
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(Map.of());
            }
            return CompletableFuture.completedFuture(Map.of("messages", new VerifiedReplacement(expected,
                    frozen, List.copyOf(candidate))));
        }

        @Override
        public Map<String, KeyStrategy> getKeyStrategys() {
            AppendStrategy ordinary = new AppendStrategy();
            return Map.of("messages", (oldValue, update) -> {
                // OverAllState passes Optional-wrapped old values to KeyStrategy, as AppendStrategy handles.
                Object current = oldValue instanceof Optional<?> optional ? optional.orElse(null) : oldValue;
                if (update instanceof VerifiedReplacement replacement) {
                    // Update-time fence, not merely a check-before-return. This fixture lock only models a
                    // serialized local owner; production still needs persistent lease/revision and run CAS.
                    synchronized (revision) {
                        if (!durable.get() || revision.get() != replacement.expectedRevision()
                                || !replacement.original().equals(current)) return current;
                        Object adopted = ordinary.apply(current, ReplaceAllWith.of(replacement.candidate()));
                        revision.incrementAndGet();
                        return adopted;
                    }
                }
                return ordinary.apply(oldValue, update);
            });
        }
    }

    private record VerifiedReplacement(long expectedRevision, List<Message> original, List<Message> candidate) { }

    private static int cutoff(List<Message> original, List<Message> candidate) {
        if (candidate.isEmpty() || !(candidate.get(0) instanceof SystemMessage)) return -1;
        int retained = candidate.size() - 1;
        int cutoff = original.size() - retained;
        if (cutoff <= 0 || !original.subList(cutoff, original.size()).equals(candidate.subList(1, candidate.size()))) return -1;
        return cutoff;
    }

    /** Whole-window pairing by ID, no +/-5 scan limit; duplicate/orphan/pending groups fail closed. */
    private static boolean completeToolGroups(List<Message> messages) {
        Set<String> calls = new HashSet<>();
        Set<String> responses = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    if (!calls.add(call.id())) return false;
                }
            } else if (message instanceof ToolResponseMessage tool) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    if (!calls.contains(response.id()) || !responses.add(response.id())) return false;
                }
            }
        }
        return calls.equals(responses);
    }

    private static SummarizationHook nativeHook(ChatModel model, Integer threshold, int keep, TokenCounter counter) {
        return SummarizationHook.builder().model(model).maxTokensBeforeSummary(threshold).messagesToKeep(keep)
                .keepFirstUserMessage(false).tokenCounter(counter).build();
    }

    private static ChatModel summaryModel(String text) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(chatResponse(text));
        return model;
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static List<Message> plainWindow() {
        return List.of(new UserMessage("old input"), new AssistantMessage("old answer"), new UserMessage("tail"));
    }

    private static AssistantMessage call(String id) {
        return AssistantMessage.builder().content("tool requested")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "fixtureTool", "{\"query\":\"fact\"}"))).build();
    }

    private static ToolResponseMessage response(String id, String data) {
        return ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(id, "fixtureTool", data))).build();
    }

    private static OverAllState state(List<Message> messages) {
        return new OverAllState(Map.of("messages", new ArrayList<>(messages)))
                .registerKeyAndStrategy("messages", new AppendStrategy());
    }

    private static List<Message> window(OverAllState state) {
        return state.<List<Message>>value("messages").orElseThrow();
    }

    private static Map<String, Object> nativeUpdate(SummarizationHook hook, OverAllState state) {
        return MessagesModelHook.beforeModelAction(hook).apply(state, CONFIG).join();
    }

    private static List<Message> replacement(Map<String, Object> update) {
        Object value = update.get("messages");
        assertThat(value).isInstanceOf(ReplaceAllWith.class);
        List<?> values = ((ReplaceAllWith<?>) value).newValues();
        assertThat(values).allMatch(Message.class::isInstance);
        return values.stream().map(Message.class::cast).toList();
    }

    private static void apply(OverAllState state, Map<String, Object> update) {
        state.updateState(update);
    }

    private static void assertArtifact(Class<?> type, String group, String artifact, String version, String expectedHash)
            throws Exception {
        String resource = "META-INF/maven/" + group + "/" + artifact + "/pom.properties";
        Properties properties = new Properties();
        try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            properties.load(input);
        }
        assertThat(properties.getProperty("version")).isEqualTo(version);
        Path path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        assertThat(hash).isEqualTo(expectedHash);
        System.out.println("ARTIFACT " + group + ":" + artifact + ":" + version + " path=" + path + " sha256=" + hash);
    }
}
