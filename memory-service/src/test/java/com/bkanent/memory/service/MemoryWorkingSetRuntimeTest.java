package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.memory.rpc.MemoryV2RpcServiceImpl;
import com.bkanent.memory.runtime.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bkanent.memory.service.MemoryArchiveServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Thin runtime against actual signed archive SQL/security, not a mocked memory service. */
class MemoryWorkingSetRuntimeTest {
    static class RuntimeFixture extends Fixture {
        final MemoryWorkingSetCache cache = new MemoryWorkingSetCache(4, 4 * 1024 * 1024, 60_000, Clock.systemUTC());
        final Map<String,Integer> calls = new HashMap<>();
        final MemoryRpcClient client;
        boolean loseWriterReply;
        boolean losePermitReply;
        RuntimeFixture() throws Exception {
            var access = new MemorySharingAccessService(security, new MemoryAccessPolicy(), sharing, sources, transactions);
            var real = new MemoryV2RpcServiceImpl(service, access, security, null, new MemoryMatterService(store, security, sources, transactions));
            var proxy = (MemoryV2RpcService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{MemoryV2RpcService.class}, (ignored, method, args) -> {
                calls.merge(method.getName(), 1, Integer::sum);
                try {
                    Object result = method.invoke(real, args);
                    if (loseWriterReply && method.getName().equals("manageWriter")
                            && ((MemoryWriterRequest) args[0]).action() == MemoryWriterRequest.Action.ACQUIRE) {
                        loseWriterReply = false;
                        throw new IllegalStateException("reply lost after durable writer acquire");
                    }
                    if (losePermitReply && method.getName().equals("requestReceivePermit")) {
                        losePermitReply = false;
                        throw new IllegalStateException("reply lost after durable receive permit");
                    }
                    return result;
                }
                catch (InvocationTargetException exception) { throw exception.getCause(); }
            });
            client = new MemoryRpcClient(proxy, CALLER, "fixture-key", identityPair.getPrivate(),
                    () -> new MemoryRpcClient.Actor(new AuthenticatedPrincipal(42L), Instant.now().getEpochSecond() + 60));
        }
        MemoryWorkingSession open(String run) { return new MemoryWorkingSession(client, cache, CONTEXT, run); }
        void append(MemoryWorkingSession session, List<MemoryMessage> messages) { session.deliver(session.permit(messages), messages); }
    }

    @Test void hotModelBoundariesReuseBodyButAlwaysRecheckCurrentSources() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run-1")) {
            f.append(session, List.of(message(1, "one"), message(2, "two")));
            for (int i = 0; i < 8; i++) session.check();
            assertEquals(1, f.calls.get("readWorkingSet"));
            assertEquals(8, f.calls.get("checkBoundary"));
            assertEquals(2, session.snapshot().messages().size());
            assertEquals(1, f.calls.get("deliverMessages"));
        }
    }

    @Test void enrolledContextRejectsUnfencedWritesAndConcurrentRun() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run-1")) {
            code(MemoryErrorCode.VERSION_CONFLICT, () -> f.open("run-2"));
            var unfenced = f.permitRequest(List.of(message(1, "forged")), List.of(), "unfenced");
            code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.requestReceivePermit(unfenced, f.sign(unfenced)));
            assertEquals(0, f.count("memory_receive_permit"));
        }
    }

    @Test void expiredFenceCannotRenewOrPublishSummaryAfterNewInstanceTakesOver() throws Exception {
        var f = new RuntimeFixture();
        try (var old = f.open("run-old")) {
            f.append(old, List.of(message(1, "one"), message(2, "two")));
            f.jdbc.update("UPDATE memory_conversation_writer SET expires_at_ms=0");
            try (var newer = f.open("run-new")) {
                assertTrue(newer.authority().fence() > old.authority().fence());
                code(MemoryErrorCode.VERSION_CONFLICT, old::renew);
                var summary = summary(List.of(message(1, "one")), old.snapshot().version().revision(), List.of(), List.of());
                var request = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, e -> new MemorySummaryRequest(e, summary, old.authority()));
                code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.submitSummary(request, f.sign(request)));
                assertEquals(0, f.count("memory_summary"));
            }
        }
    }

    @Test void exactAdmittedFactMayStillBeDeliveredByServiceAfterWriterLoss() throws Exception {
        var f = new RuntimeFixture();
        try (var old = f.open("run-old")) {
            var messages = List.of(message(1, "admitted before expiry"));
            String permit = old.permit(messages);
            f.jdbc.update("UPDATE memory_conversation_writer SET expires_at_ms=0");
            f.auth.allowed = false;
            assertEquals(1, f.client.deliver(CONTEXT, ASSOCIATION, permit, messages).version().lastSequence());
            assertEquals(1, f.count("memory_message"));
        }
    }

    @Test void writerAcquireMissingReplyCanBeRetriedWithSameSignedRequestIdentity() throws Exception {
        var f = new RuntimeFixture();
        var request = f.request(MemoryV2Contract.OPERATION_WRITER, e -> new MemoryWriterRequest(e, CONTEXT, MemoryWriterRequest.Action.ACQUIRE, "run", 0));
        var one = f.service.manageWriter(request, f.sign(request));
        var two = f.service.manageWriter(request, f.sign(request));
        assertEquals(one.authority(), two.authority());
        assertEquals(1, f.count("memory_conversation_writer"));
    }

    @Test void clientRetriesLostWriterAcquireWithoutCreatingANewFence() throws Exception {
        var f = new RuntimeFixture();
        f.loseWriterReply = true;
        code(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.open("run"));
        try (var recovered = f.open("run")) {
            assertEquals(1, recovered.authority().fence());
            assertEquals(2, f.calls.get("manageWriter"));
            assertEquals(1, f.count("memory_conversation_writer"));
        }
    }

    @Test void lostPermitReplyUsesStableRequestIdentityWithinCurrentWriterFence() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            var messages = List.of(message(1, "stable event"));
            f.losePermitReply = true;
            code(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> session.permit(messages));
            assertDoesNotThrow(() -> session.permit(messages));
            assertEquals(1, f.count("memory_receive_permit"));
            assertEquals(2, f.calls.get("requestReceivePermit"));
        }
    }

    @Test void warmReadChecksOnlySourceMetadataAndColdReadStillVerifiesPayloadHash() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            f.append(session, List.of(message(1, "immutable cached body")));
            f.jdbc.update("UPDATE memory_message SET payload_json='broken-payload'");
            session.check();
            assertEquals("immutable cached body", session.snapshot().messages().get(0).text());
            f.jdbc.update("UPDATE memory_conversation SET policy_version='policy-rotated'");
            code(MemoryErrorCode.MEMORY_UNAVAILABLE, session::check);
            assertNull(f.cache.get(CONTEXT), "a policy revision forces a cold reload instead of reusing stale bodies");
            f.cache.remove(CONTEXT);
            code(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.client.read(CONTEXT));
        }
    }

    @Test void withdrawalOfOnePrivateSourceEvictsWarmBodyAndOldCheckpointCannotReuseIt() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            f.append(session, List.of(message(1, "sensitive old fact")));
            var captured = session.snapshot();
            f.jdbc.update("UPDATE memory_message SET state='WITHDRAWN'");
            code(MemoryErrorCode.WITHDRAWN, session::check);
            assertNull(f.cache.get(CONTEXT));
            code(MemoryErrorCode.WITHDRAWN, () -> f.client.check(captured, MemoryWorkingSession.dependencies(captured)));
        }
    }

    @Test void assistantDerivedCopyCannotOutliveWithdrawnSourceEvenWithoutSummary() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            var user = message(1, "private original");
            f.append(session, List.of(user));
            var ordinary = build(2, MemoryMessage.Role.ASSISTANT, "repeated private original", List.of(), List.of());
            var dependencies = List.of(new MemorySourceDependency(user.source(), null));
            var draft = new MemoryMessage(ordinary.messageId(), CONTEXT, 2, ASSOCIATION, ordinary.role(), ordinary.text(), List.of(), List.of(), ordinary.source(), dependencies);
            var derived = new MemoryMessage(draft.messageId(), CONTEXT, 2, ASSOCIATION, draft.role(), draft.text(), List.of(), List.of(),
                    new MemorySourceRef(draft.source().kind(), CONTEXT, draft.messageId(), "event-2", 2L,
                            new MemoryRevision(1, MemoryCanonicalHash.messageHash(draft), draft.source().version().schemaVersion())), dependencies);
            f.append(session, List.of(derived));
            var withdrawal = f.request(MemoryV2Contract.OPERATION_WITHDRAW, e -> new MemoryWithdrawalRequest(e, CONTEXT, "event-1", false));
            f.service.withdrawSource(withdrawal, f.sign(withdrawal));
            assertTrue(f.client.read(CONTEXT).messages().isEmpty());
            code(MemoryErrorCode.WITHDRAWN, session::check);
        }
    }

    @Test void safeCompactionCommitsCoverageThenRecoversSummaryPlusEntireTail() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            f.append(session, List.of(message(1, "one"), message(2, "two"), message(3, "three"), message(4, "four")));
            var coordinator = new MemoryCompactionCoordinator();
            assertTrue(coordinator.compact(session, 2, MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE, null,
                    input -> List.of(new MemorySummary.Item(MemorySummary.ItemKind.NEGATION, "not a permanent default", List.of(input.dependencies().get(0)))), () -> { }));
            assertEquals(2, session.snapshot().summaries().get(0).coveredRange().throughSequence());
            assertEquals(List.of(3L, 4L), session.snapshot().messages().stream().map(MemoryMessage::sequence).toList());
            assertEquals(4, f.count("memory_message"));
        }
        f.cache.remove(CONTEXT);
        try (var recovered = f.open("run-recovery")) {
            assertEquals(1, recovered.snapshot().summaries().size());
            assertEquals(List.of(3L, 4L), recovered.snapshot().messages().stream().map(MemoryMessage::sequence).toList());
        }
    }

    @Test void compactionScansPastTwentyMessagesAndNeverCutsAnOpenToolGroup() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            var messages = new ArrayList<MemoryMessage>();
            var call = new MemoryToolCall("long-call", "draft", "draft", MemoryJsonValue.object(Map.of()));
            messages.add(build(1, MemoryMessage.Role.ASSISTANT, null, List.of(call), List.of()));
            for (int sequence = 2; sequence <= 21; sequence++) messages.add(message(sequence, "intervening-" + sequence));
            messages.add(tool(22, MemoryToolResponse.ExecutionState.SUCCEEDED, "complete response", "long-call"));
            f.append(session, messages);
            assertFalse(new MemoryCompactionCoordinator().compact(session, 2, MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE,
                    null, input -> List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT, "must not split", input.dependencies())), () -> { }));
            assertEquals(22, session.snapshot().messages().size());
            assertEquals(0, f.count("memory_summary"));
            f.append(session, List.of(message(23, "recent input")));
            var anchor = new MemoryToolResultAnchor(messages.get(21).source(), "long-call", "draft", MemoryToolResponse.ExecutionState.SUCCEEDED);
            assertTrue(new MemoryCompactionCoordinator().compactStructured(session, 1, MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE,
                    null, input -> new MemoryCompactionCoordinator.Candidate(List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT,
                            "complete paired result", input.dependencies())), List.of(), List.of(anchor)), () -> { }));
            assertEquals(List.of(new MemoryCoveredRange(1, 22)), session.snapshot().summaries().get(0).toolGroupRanges());
            assertEquals(List.of(anchor), session.snapshot().summaries().get(0).toolResults());
            f.append(session, List.of(message(24, "another input")));
            assertTrue(new MemoryCompactionCoordinator().compactStructured(session, 1, MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE,
                    null, input -> new MemoryCompactionCoordinator.Candidate(List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT,
                            "preserve existing anchor", input.dependencies())), List.of(), List.of()), () -> { }));
            assertEquals(List.of(anchor), session.snapshot().summaries().get(0).toolResults());
        }
    }

    @Test void generatorFailureKeepsOriginalWindowAndNeverClaimsCoverage() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            f.append(session, List.of(message(1, "one"), message(2, "two")));
            var before = session.snapshot();
            code(MemoryErrorCode.INVALID_EXTRACTION, () -> new MemoryCompactionCoordinator().compact(session, 1,
                    MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE, null, input -> List.of(), () -> { }));
            assertEquals(before, session.snapshot());
            assertEquals(0, f.count("memory_summary"));
        }
    }

    @Test void newInputDuringGenerationRejectsStaleCoverageAndPreservesAllMessages() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            f.append(session, List.of(message(1, "one"), message(2, "two")));
            code(MemoryErrorCode.VERSION_CONFLICT, () -> new MemoryCompactionCoordinator().compact(session, 1,
                    MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE, null, input -> {
                        f.append(session, List.of(message(3, "new input")));
                        return List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT, "stale candidate", input.dependencies()));
                    }, () -> { }));
            assertEquals(0, f.count("memory_summary"));
            assertEquals(List.of(1L, 2L, 3L), session.snapshot().messages().stream().map(MemoryMessage::sequence).toList());
        }
        try (var recovered = f.open("recovery")) {
            assertEquals(List.of(1L, 2L, 3L), recovered.snapshot().messages().stream().map(MemoryMessage::sequence).toList());
        }
    }

    @Test void closureAndTokenCandidatesCompeteOnOneConversationRevision() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            var start = MemoryMatterServiceTest.message(CONTEXT, 1, new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房"));
            f.append(session, List.of(start));
            var matter = session.transitionMatter(start).matter();
            var close = MemoryMatterServiceTest.message(CONTEXT, 2, new MemoryMatterIntent(MemoryMatterIntent.Action.CLOSE, matter.matterId(), 1, null));
            f.append(session, List.of(close));
            var closed = session.transitionMatter(close).matter();
            f.append(session, List.of(message(3, "recent unrelated input")));
            var coordinator = new MemoryCompactionCoordinator();
            code(MemoryErrorCode.VERSION_CONFLICT, () -> coordinator.compact(session, 1,
                    MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE, null, input -> {
                        var closure = closed.closure();
                        assertTrue(coordinator.compact(session, 1, MemoryCompactionCoordinator.Trigger.CONFIRMED_CLOSURE, closure,
                                winner -> List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT, "confirmed candidate", winner.dependencies())), () -> { }));
                        return List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT, "losing token candidate", input.dependencies()));
                    }, () -> { }));
            assertEquals(1, f.count("memory_summary"));
            assertEquals(2, session.snapshot().summaries().get(0).coveredRange().throughSequence());
            assertEquals(List.of(3L), session.snapshot().messages().stream().map(MemoryMessage::sequence).toList());
        }
        try (var recovered = f.open("recovery")) {
            assertEquals(1, recovered.snapshot().summaries().size());
            assertEquals(List.of(3L), recovered.snapshot().messages().stream().map(MemoryMessage::sequence).toList());
        }
    }

    @Test void sourceRevokedDuringGenerationCannotCommitOrReachNewPrompt() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            f.append(session, List.of(message(1, "one"), message(2, "two")));
            code(MemoryErrorCode.WITHDRAWN, () -> new MemoryCompactionCoordinator().compact(session, 1,
                    MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE, null, input -> {
                        f.jdbc.update("UPDATE memory_message SET state='WITHDRAWN' WHERE sequence_no=1");
                        return List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT, "not safe anymore", input.dependencies()));
                    }, () -> { }));
            assertEquals(0, f.count("memory_summary"));
            assertNull(f.cache.get(CONTEXT));
        }
    }
    @Test void closureNeedsPersistedClosedMatterNotJustAUserSource() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            f.append(session, List.of(message(1, "done"), message(2, "recent")));
            var closure = new MemoryMatter.ClosureEvidence(MemoryMatter.ClosureKind.USER_CONFIRMATION, MemoryWorkingSession.dependencies(session.snapshot()));
            code(MemoryErrorCode.FORBIDDEN, () -> new MemoryCompactionCoordinator().compact(session, 1, MemoryCompactionCoordinator.Trigger.CONFIRMED_CLOSURE, closure,
                    input -> { fail("unverified closure cannot enter generator"); return List.of(); }, () -> { }));
        }
    }
    @Test void closureKeepsUnrelatedOpenMaterialButTokenPressureCanUseItsOwnSafeCoverage() throws Exception {
        var f = new RuntimeFixture();
        try (var session = f.open("run")) {
            f.append(session, List.of(message(1, "unrelated open matter")));
            var start = MemoryMatterServiceTest.message(CONTEXT, 2, new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房")); f.append(session, List.of(start));
            var matter = session.transitionMatter(start).matter();
            var close = MemoryMatterServiceTest.message(CONTEXT, 3, new MemoryMatterIntent(MemoryMatterIntent.Action.CLOSE, matter.matterId(), 1, null)); f.append(session, List.of(close));
            var closed = session.transitionMatter(close).matter(); var coordinator = new MemoryCompactionCoordinator();
            assertFalse(coordinator.compact(session, 1, MemoryCompactionCoordinator.Trigger.CONFIRMED_CLOSURE, closed.closure(), input -> { fail("unrelated prefix stays"); return List.of(); }, () -> { }));
            assertEquals(3, session.snapshot().messages().size());
            assertTrue(coordinator.compact(session, 1, MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE, null, input -> List.of(new MemorySummary.Item(MemorySummary.ItemKind.UNRESOLVED, "unrelated open matter", input.dependencies())), () -> { }));
            assertEquals(MemoryMatter.State.CLOSED, session.snapshot().matters().get(0).state());
        }
    }

    @Test void committedSummaryWithLostRunAdoptionIsRecoveredWithoutRerunningTools() throws Exception {
        var f = new RuntimeFixture();
        AtomicInteger checks = new AtomicInteger();
        try (var session = f.open("run")) {
            f.append(session, List.of(message(1, "one"), message(2, "two")));
            assertThrows(IllegalStateException.class, () -> new MemoryCompactionCoordinator().compact(session, 1,
                    MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE, null,
                    input -> List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT, "fact", input.dependencies())),
                    () -> { if (checks.incrementAndGet() == 2) throw new IllegalStateException("run lease lost"); }));
            assertEquals(1, f.count("memory_summary"));
            assertNull(f.cache.get(CONTEXT));
        }
        try (var recovery = f.open("recovery")) {
            assertEquals(1, recovery.snapshot().summaries().size());
            assertEquals(List.of(2L), recovery.snapshot().messages().stream().map(MemoryMessage::sequence).toList());
        }
    }

    @Test void sharedOwnerDoesNotAuthorizeAnotherAgentAndServiceCannotCheckBoundaries() throws Exception {
        var f = new RuntimeFixture();
        var other = new MemoryContextKey("42", "marketing", "other-agent", "conversation-1");
        code(MemoryErrorCode.FORBIDDEN, () -> new MemoryWorkingSession(f.client, f.cache, other, "run"));
        var request = f.request(MemoryV2Contract.OPERATION_BOUNDARY, e -> new MemoryBoundaryRequest(e, CONTEXT,
                new MemoryConversationVersion(0, 0, "archive-v2.1"), List.of()));
        var userProof = f.sign(request);
        var forged = new MemoryRequestProof(userProof.keyId(), userProof.issuedAtEpochSecond(), userProof.expiresAtEpochSecond(), userProof.nonce(),
                userProof.signature(), MemoryRequestProof.Actor.SERVICE, userProof.signatureVersion(), userProof.algorithm());
        code(MemoryErrorCode.AUTHENTICATION_FAILED, () -> f.service.checkBoundary(request, forged));
    }
}
