package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.memory.config.MemoryV2SecurityProperties;
import com.bkanent.memory.config.MemoryExperienceProperties;
import com.bkanent.memory.rpc.MemoryV2RpcServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQL/transactions/signatures. H2 is only the storage engine; no mocked archive or policy. */
class MemoryArchiveServiceTest {
    static final MemoryContextKey CONTEXT = new MemoryContextKey("42", "marketing", "copy-agent", "conversation-1");
    static final MemoryAssociation ASSOCIATION = new MemoryAssociation("matter-1", "run-1", "task-1", null);
    static final String CALLER = "marketing-service";
    static final String ZERO = "0".repeat(64);

    @Test void exactPermitAndReceiptSurviveLostReplyAndServiceRestart() throws Exception {
        Fixture f = new Fixture();
        var message = message(1, "hello");
        var permitRequest = f.permitRequest(List.of(message), List.of(), "permit-request");
        var permit = f.service.requestReceivePermit(permitRequest, f.sign(permitRequest));
        assertEquals(permit, f.service.requestReceivePermit(permitRequest, f.sign(permitRequest)));
        var request = f.delivery(permit.permitId(), List.of(message));
        var first = f.service.deliverMessages(request, f.sign(request));
        f.restart();
        var retried = f.service.deliverMessages(request, f.signService(request));
        assertEquals(first, retried);
        assertEquals(1, f.count("memory_message"));
        assertEquals(1, f.count("memory_processing_job"));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_receive_event WHERE receipt_json IS NOT NULL", Integer.class));
        assertEquals(List.of(message), f.read().messages());
    }

    @Test void changedContentCannotUseAnExactPermitEvenWithFreshValidSignature() throws Exception {
        Fixture f = new Fixture();
        var permit = f.permit(List.of(message(1, "original")));
        var altered = f.delivery(permit.permitId(), List.of(message(1, "altered")));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.deliverMessages(altered, f.signService(altered)));
        assertEquals(0, f.count("memory_message"));
        assertEquals(0, f.count("memory_processing_job"));
    }

    @Test void serviceDeliverySurvivesUserExpiryButReadAndPermitRequireOriginalActorAgain() throws Exception {
        Fixture f = new Fixture();
        var message = message(1, "late original fact");
        var permit = f.permit(List.of(message));
        f.auth.allowed = false;
        f.restart();
        var delivery = f.delivery(permit.permitId(), List.of(message));
        int calls = f.auth.calls;
        f.service.deliverMessages(delivery, f.signService(delivery));
        assertEquals(calls, f.auth.calls, "SERVICE cannot impersonate an interactive user");
        code(MemoryErrorCode.FORBIDDEN, f::read);
        var nextPermit = f.permitRequest(List.of(message(2, "new input")), List.of(), "next");
        code(MemoryErrorCode.FORBIDDEN, () -> f.service.requestReceivePermit(nextPermit, f.sign(nextPermit)));
        f.auth.allowed = true;
        assertEquals(List.of(message), f.read().messages());
    }

    @Test void forgedServiceModeReadOrPermitDoesNotBecomeUserAuthentication() throws Exception {
        Fixture f = new Fixture();
        var request = f.request(MemoryV2Contract.OPERATION_WORKING_SET, envelope -> new MemoryWorkingSetRequest(envelope, CONTEXT));
        var user = f.sign(request);
        var forged = new MemoryRequestProof(user.keyId(), user.issuedAtEpochSecond(), user.expiresAtEpochSecond(), user.nonce(),
                user.signature(), MemoryRequestProof.Actor.SERVICE, user.signatureVersion(), user.algorithm());
        code(MemoryErrorCode.AUTHENTICATION_FAILED, () -> f.service.readWorkingSet(request, forged));
        var permit = f.permitRequest(List.of(message(1, "hi")), List.of(), "p1");
        code(MemoryErrorCode.AUTHENTICATION_FAILED, () -> f.service.requestReceivePermit(permit, forged));
        assertEquals(0, f.count("memory_receive_permit"));
    }

    @Test void callerContextAndCurrentDeploymentServicePermissionAreAllBound() throws Exception {
        Fixture f = new Fixture();
        var message = message(1, "hi");
        var permit = f.permit(List.of(message));
        var delivery = f.delivery(permit.permitId(), List.of(message));
        f.caller.setServiceDelivery(false);
        code(MemoryErrorCode.FORBIDDEN, () -> f.service.deliverMessages(delivery, f.signService(delivery)));
        f.caller.setServiceDelivery(true);
        var other = f.request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES, "other-service", UUID.randomUUID().toString(),
                envelope -> new MemoryDeliveryRequest(envelope, CONTEXT, ASSOCIATION, permit.permitId(), List.of(message)));
        code(MemoryErrorCode.FORBIDDEN, () -> f.service.deliverMessages(other, f.signService(other)));
        var changedContext = new MemoryContextKey("42", "marketing", "copy-agent", "another-session");
        var wrong = f.request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES,
                envelope -> new MemoryDeliveryRequest(envelope, changedContext, ASSOCIATION, permit.permitId(), List.of(message)));
        code(MemoryErrorCode.FORBIDDEN, () -> f.service.deliverMessages(wrong, f.signService(wrong)));
    }

    @Test void sameRequestIdWithDifferentPermitBodyConflicts() throws Exception {
        Fixture f = new Fixture();
        var first = f.permitRequest(List.of(message(1, "one")), List.of(), "same-request");
        f.service.requestReceivePermit(first, f.sign(first));
        var second = f.permitRequest(List.of(message(1, "two")), List.of(), "same-request");
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.requestReceivePermit(second, f.sign(second)));
        assertEquals(1, f.count("memory_receive_permit"));
    }

    @Test void outOfOrderBatchRollsBackTruthReceiptsAndJobsThenCanRetryInOrder() throws Exception {
        Fixture f = new Fixture();
        var messages = List.of(message(1, "first"), message(2, "second"), message(3, "third"));
        var permit = f.permit(messages);
        var bad = f.delivery(permit.permitId(), List.of(messages.get(0), messages.get(2)));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.deliverMessages(bad, f.signService(bad)));
        assertEquals(0, f.count("memory_message"));
        assertEquals(0, f.count("memory_processing_job"));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_receive_event WHERE receipt_json IS NOT NULL", Integer.class));
        var good = f.delivery(permit.permitId(), messages);
        assertEquals(3, f.service.deliverMessages(good, f.signService(good)).version().lastSequence());
    }

    @Test void duplicateDeliveryAcrossInstancesCreatesOnlyOneFactAndOneJob() throws Exception {
        Fixture f = new Fixture();
        var messages = List.of(message(1, "once"));
        var permit = f.permit(messages);
        var request = f.delivery(permit.permitId(), messages);
        var one = f.newService();
        var two = f.newService();
        var first = CompletableFuture.supplyAsync(() -> one.deliverMessages(request, f.signService(request)));
        var second = CompletableFuture.supplyAsync(() -> two.deliverMessages(request, f.signService(request)));
        assertEquals(first.join(), second.join());
        assertEquals(1, f.count("memory_message"));
        assertEquals(1, f.count("memory_processing_job"));
    }

    @Test void nonceReplayAndExpiredPermitDoNotAcknowledgeNewTruth() throws Exception {
        Fixture f = new Fixture();
        var messages = List.of(message(1, "once"));
        var permit = f.permit(messages);
        var request = f.delivery(permit.permitId(), messages);
        var proof = f.signService(request);
        f.service.deliverMessages(request, proof);
        code(MemoryErrorCode.REQUEST_REPLAYED, () -> f.service.deliverMessages(request, proof));
        f.jdbc.update("UPDATE memory_receive_permit SET expires_at=?", java.time.LocalDateTime.ofInstant(Instant.now().minusSeconds(1), java.time.ZoneOffset.UTC));
        code(MemoryErrorCode.AUTHENTICATION_EXPIRED, () -> f.service.deliverMessages(request, f.signService(request)));
        assertEquals(1, f.count("memory_message"));
    }

    @Test void onlyAcceptedInvocationCanReserveExactlyOneLaterResult() throws Exception {
        Fixture f = new Fixture();
        var accepted = tool(1, MemoryToolResponse.ExecutionState.ACCEPTED, "accepted", "call-1");
        f.append(List.of(accepted));
        var admission = new MemoryResultAdmission(accepted.source(), "call-1", "event-2", "message-2", 2);
        var request = f.permitRequest(List.of(), List.of(admission), "result-reservation");
        var permit = f.service.requestReceivePermit(request, f.sign(request));
        var result = tool(2, MemoryToolResponse.ExecutionState.SUCCEEDED, "completed", "call-1");
        f.auth.allowed = false;
        f.restart();
        var delivery = f.delivery(permit.permitId(), List.of(result));
        f.service.deliverMessages(delivery, f.signService(delivery));
        assertEquals(2, f.count("memory_message"));
        f.auth.allowed = true;
        var alternate = new MemoryResultAdmission(accepted.source(), "call-1", "event-3", "message-3", 3);
        var conflict = f.permitRequest(List.of(), List.of(alternate), "another-reservation");
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.requestReceivePermit(conflict, f.sign(conflict)));
        var modified = f.delivery(permit.permitId(), List.of(tool(2, MemoryToolResponse.ExecutionState.FAILED, "changed", "call-1")));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.deliverMessages(modified, f.signService(modified)));
    }

    @Test void modelProposedCallAndWrongCallOrNewUserMessageCannotUseResultPermission() throws Exception {
        Fixture f = new Fixture();
        var proposal = build(1, MemoryMessage.Role.ASSISTANT, null,
                List.of(new MemoryToolCall("call-1", "marketing.draft", "draft", MemoryJsonValue.object(Map.of()))), List.of());
        f.append(List.of(proposal));
        var forged = f.permitRequest(List.of(), List.of(new MemoryResultAdmission(proposal.source(), "call-1", "event-2", "message-2", 2)), "forged");
        code(MemoryErrorCode.FORBIDDEN, () -> f.service.requestReceivePermit(forged, f.sign(forged)));

        Fixture acceptedFixture = new Fixture();
        var accepted = tool(1, MemoryToolResponse.ExecutionState.ACCEPTED, "accepted", "call-1");
        acceptedFixture.append(List.of(accepted));
        var reserve = acceptedFixture.permitRequest(List.of(), List.of(new MemoryResultAdmission(accepted.source(), "call-1", "event-2", "message-2", 2)), "reserve");
        var permit = acceptedFixture.service.requestReceivePermit(reserve, acceptedFixture.sign(reserve));
        for (var message : List.of(message(2, "injected user"), tool(2, MemoryToolResponse.ExecutionState.SUCCEEDED, "wrong call", "call-2"))) {
            var delivery = acceptedFixture.delivery(permit.permitId(), List.of(message));
            code(MemoryErrorCode.FORBIDDEN, () -> acceptedFixture.service.deliverMessages(delivery, acceptedFixture.signService(delivery)));
        }
        assertEquals(1, acceptedFixture.count("memory_message"));
    }

    @Test void committedSummaryAndUncoveredTailRecoverWithoutReadingV1() throws Exception {
        Fixture f = new Fixture();
        var messages = List.of(message(1, "one"), message(2, "two"));
        f.append(messages);
        var summary = summary(messages, 1, List.of(), List.of());
        var request = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, summary));
        var receipt = f.service.submitSummary(request, f.sign(request));
        assertEquals(receipt, f.service.submitSummary(request, f.sign(request)));
        f.append(List.of(message(3, "tail")));
        f.restart();
        assertEquals(List.of(summary), f.read().summaries());
        assertEquals(List.of(message(3, "tail")), f.read().messages());
        assertEquals(1, f.count("memory_summary"));
        assertEquals(3, f.count("memory_message"));
    }

    @Test void staleConversationRevisionAndMissingCoverageNeverAdoptSummary() throws Exception {
        Fixture f = new Fixture();
        var first = message(1, "one");
        f.append(List.of(first));
        var candidate = summary(List.of(first), 1, List.of(), List.of());
        f.append(List.of(message(2, "new concurrent input")));
        var stale = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, candidate));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.submitSummary(stale, f.sign(stale)));
        assertTrue(f.read().summaries().isEmpty());
        assertEquals(2, f.read().messages().size());
        var future = summary(List.of(message(1, "one"), message(2, "new concurrent input"), message(3, "not archived")), 2, List.of(), List.of());
        var unconfirmed = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, future));
        code(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED, () -> f.service.submitSummary(unconfirmed, f.sign(unconfirmed)));
        assertEquals(0, f.count("memory_summary"));
    }

    @Test void runCheckpointRevisionCannotBeTreatedAsMemoryConversationCas() throws Exception {
        Fixture f = new Fixture();
        var first = message(1, "one");
        f.append(List.of(first));
        var original = summary(List.of(first), 1, List.of(), List.of());
        var withRun = new MemorySummary(original.summaryId(), CONTEXT, ASSOCIATION, original.version(), 1,
                new MemoryRunVersion("run-1", 1), original.coveredRange(), List.of(), original.items(), List.of(), original.dependencies());
        var request = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, withRun));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.submitSummary(request, f.sign(request)));
        assertEquals(0, f.count("memory_summary"));
    }

    @Test void incompleteAndApprovalOrUnknownToolGroupsCannotBeCompressed() throws Exception {
        for (var state : List.of(MemoryToolResponse.ExecutionState.SUCCEEDED, MemoryToolResponse.ExecutionState.WAITING_APPROVAL,
                MemoryToolResponse.ExecutionState.OUTCOME_UNKNOWN)) {
            Fixture f = new Fixture();
            var assistant = build(1, MemoryMessage.Role.ASSISTANT, null,
                    List.of(new MemoryToolCall("call-1", "marketing.draft", "draft", MemoryJsonValue.object(Map.of()))), List.of());
            var response = tool(2, state, "result", "call-1");
            f.append(List.of(assistant, response));
            var incomplete = summary(List.of(assistant), 1, List.of(), List.of());
            var bad = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, incomplete));
            code(MemoryErrorCode.INCOMPLETE_TOOL_GROUP, () -> f.service.submitSummary(bad, f.sign(bad)));
            var complete = summary(List.of(assistant, response), 1, List.of(new MemoryCoveredRange(1, 2)), List.of());
            var request = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, complete));
            if (state == MemoryToolResponse.ExecutionState.SUCCEEDED) assertTrue(f.service.submitSummary(request, f.sign(request)).accepted());
            else code(MemoryErrorCode.INCOMPLETE_TOOL_GROUP, () -> f.service.submitSummary(request, f.sign(request)));
        }
    }

    @Test void withdrawalInvalidatesSummaryAndEntityThenLateRetryCannotReviveFact() throws Exception {
        Fixture f = new Fixture();
        var first = message(1, "private fact");
        var permit = f.permit(List.of(first));
        var delivery = f.delivery(permit.permitId(), List.of(first));
        f.service.deliverMessages(delivery, f.signService(delivery));
        var entity = new MemoryEntityAnchor("entity-1", CONTEXT, "matter-1", first.source().version(), List.of("the listing"),
                "synthetic entity", List.of(new MemorySourceDependency(first.source(), null)));
        var summary = summary(List.of(first), 1, List.of(), List.of(entity));
        var submit = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, summary));
        f.service.submitSummary(submit, f.sign(submit));
        assertEquals(1, f.entities(null, 10).entities().size());
        var withdraw = f.request(MemoryV2Contract.OPERATION_WITHDRAW, envelope -> new MemoryWithdrawalRequest(envelope, CONTEXT, "event-1", false));
        assertEquals(MemoryState.WITHDRAWN, f.service.withdrawSource(withdraw, f.sign(withdraw)).state());
        f.restart();
        assertTrue(f.read().messages().isEmpty());
        assertTrue(f.read().summaries().isEmpty());
        assertTrue(f.entities(null, 10).entities().isEmpty());
        code(MemoryErrorCode.WITHDRAWN, () -> f.service.deliverMessages(delivery, f.signService(delivery)));
        assertEquals(1, f.count("memory_message"), "archive policy must not rewrite domain execution facts");
    }

    @Test void deletionErasesLocalBodiesButReportsPendingExternalCleanupAndRetainsTombstone() throws Exception {
        Fixture f = new Fixture();
        var first = message(1, "body-to-erase");
        var permit = f.permit(List.of(first));
        var acceptedDelivery = f.delivery(permit.permitId(), List.of(first));
        f.service.deliverMessages(acceptedDelivery, f.signService(acceptedDelivery));
        var summary = summary(List.of(first), 1, List.of(), List.of());
        var submit = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, summary));
        f.service.submitSummary(submit, f.sign(submit));
        var deletion = f.request(MemoryV2Contract.OPERATION_WITHDRAW, envelope -> new MemoryWithdrawalRequest(envelope, CONTEXT, "event-1", true));
        assertEquals(MemoryState.DELETION_PENDING, f.service.withdrawSource(deletion, f.sign(deletion)).state());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_message WHERE payload_json IS NOT NULL", Integer.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_summary WHERE payload_json IS NOT NULL", Integer.class));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_processing_job WHERE job_kind='PRIVACY_CLEANUP' AND state='PENDING'", Integer.class));
        assertEquals(MemoryPrivacyCleanupState.PENDING, f.privacyStatus("event-1").cleanupState());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_privacy_deletion WHERE source_hash<>? OR source_event_id<>?", Integer.class,
                MemoryCanonicalHash.messageHash(first), "event-1"));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_privacy_deletion WHERE source_event_id=? AND "
                + "(source_hash IS NULL OR action_kind<>'DELETE' OR cleanup_state<>'PENDING')", Integer.class, "event-1"));
        new MemoryPrivacyCleanupWorker(f.store, f.transactions, List.of(), 4, 3, 60, 1, Clock.systemUTC()).runOnce();
        var completed = f.privacyStatus("event-1");
        assertEquals(MemoryPrivacyCleanupState.COMPLETE, completed.cleanupState());
        assertEquals(MemoryState.DELETED, completed.sourceState());
        assertNotNull(completed.completedAt());
        assertEquals("DELETED", f.jdbc.queryForObject("SELECT state FROM memory_message WHERE context_id=? AND source_event_id=?", String.class,
                MemoryArchiveStore.key(CONTEXT), "event-1"));
        var late = f.permitRequest(List.of(first), List.of(), "late-permit");
        code(MemoryErrorCode.DELETED, () -> f.service.requestReceivePermit(late, f.sign(late)));
        var lateDelivery = f.delivery(permit.permitId(), List.of(first));
        code(MemoryErrorCode.DELETED, () -> f.service.deliverMessages(lateDelivery, f.signService(lateDelivery)));
        assertEquals(1, f.count("memory_message"));
    }

    @Test void privacyProjectionFailureStaysPendingAndRetriesWithOnlyExactSourceIdentity() throws Exception {
        Fixture f = new Fixture();
        var first = message(1, "private body must never reach the cleaner");
        f.append(List.of(first));
        var request = f.request(MemoryV2Contract.OPERATION_WITHDRAW,
                envelope -> new MemoryWithdrawalRequest(envelope, CONTEXT, "event-1", true));
        f.service.withdrawSource(request, f.sign(request));
        var initial = Instant.now().plusSeconds(10);
        var clock = Clock.fixed(initial, ZoneOffset.UTC);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MemoryPrivacyProjectionCleaner cleaner = new MemoryPrivacyProjectionCleaner() {
            public String projectionId() { return "fixture-index"; }
            public void remove(MemoryContextKey context, String sourceEventId, String sourceHash) {
                assertEquals(CONTEXT, context);
                assertEquals("event-1", sourceEventId);
                assertEquals(MemoryCanonicalHash.messageHash(first), sourceHash);
                if (calls.getAndIncrement() == 0) throw new IllegalStateException("external projection unavailable");
            }
        };
        new MemoryPrivacyCleanupWorker(f.store, f.transactions, List.of(cleaner), 4, 3, 60, 1, clock).runOnce();
        assertEquals(MemoryPrivacyCleanupState.RETRY, f.privacyStatus("event-1").cleanupState());
        assertEquals(MemoryState.DELETION_PENDING, f.privacyStatus("event-1").sourceState());
        new MemoryPrivacyCleanupWorker(f.store, f.transactions, List.of(cleaner), 4, 3, 60, 1,
                Clock.fixed(initial.plusSeconds(2), ZoneOffset.UTC)).runOnce();
        assertEquals(MemoryPrivacyCleanupState.COMPLETE, f.privacyStatus("event-1").cleanupState());
        assertEquals(2, calls.get());
        assertEquals("COMPLETE", f.jdbc.queryForObject("SELECT cleanup_state FROM memory_privacy_cleanup_target WHERE "
                + "context_id=? AND source_event_id=? AND projection_id=?", String.class,
                MemoryArchiveStore.key(CONTEXT), "event-1", "fixture-index"));
    }

    @Test void privacyCleanupStatusIsOwnerScopedAndReturnsNoBody() throws Exception {
        Fixture f = new Fixture();
        f.append(List.of(message(1, "private state")));
        var status = f.privacyStatus("event-1");
        assertEquals(MemoryPrivacyCleanupState.NOT_REQUESTED, status.cleanupState());
        assertNull(status.updatedAt());
        assertNull(status.completedAt());
        var foreign = new MemoryContextKey("43", CONTEXT.domainId(), CONTEXT.agentId(), CONTEXT.conversationId());
        var request = f.request(MemoryV2Contract.OPERATION_PRIVACY_STATUS,
                envelope -> new MemoryPrivacyCleanupStatusRequest(envelope, foreign, "event-1"));
        code(MemoryErrorCode.AUTHENTICATION_FAILED, () -> f.service.privacyCleanupStatus(request, f.sign(request)));
    }

    @Test void privacyCleanupLeasePreventsTwoInstancesFromRunningTheSameProjection() throws Exception {
        Fixture f = new Fixture();
        var message = message(1, "private projection");
        f.append(List.of(message));
        var request = f.request(MemoryV2Contract.OPERATION_WITHDRAW,
                envelope -> new MemoryWithdrawalRequest(envelope, CONTEXT, "event-1", true));
        f.service.withdrawSource(request, f.sign(request));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MemoryPrivacyProjectionCleaner cleaner = new MemoryPrivacyProjectionCleaner() {
            public String projectionId() { return "serialized-index"; }
            public void remove(MemoryContextKey context, String sourceEventId, String sourceHash) {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test gate timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted");
                }
            }
        };
        var first = new MemoryPrivacyCleanupWorker(f.store, f.transactions, List.of(cleaner), 4, 3, 60, 1, Clock.systemUTC());
        var second = new MemoryPrivacyCleanupWorker(f.store, f.transactions, List.of(cleaner), 4, 3, 60, 1, Clock.systemUTC());
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(first::runOnce);
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            second.runOnce();
            assertEquals(1, calls.get());
            release.countDown();
            running.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            assertEquals(MemoryPrivacyCleanupState.COMPLETE, f.privacyStatus("event-1").cleanupState());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test void withdrawnSourceCanEscalateToDeletionButCannotBeRevivedByARepeat() throws Exception {
        Fixture f = new Fixture();
        var first = message(1, "retract then erase");
        f.append(List.of(first));
        var withdraw = f.request(MemoryV2Contract.OPERATION_WITHDRAW,
                envelope -> new MemoryWithdrawalRequest(envelope, CONTEXT, "event-1", false));
        assertEquals(MemoryState.WITHDRAWN, f.service.withdrawSource(withdraw, f.sign(withdraw)).state());
        new MemoryPrivacyCleanupWorker(f.store, f.transactions, List.of(), 4, 3, 60, 1, Clock.systemUTC()).runOnce();
        assertEquals(MemoryPrivacyCleanupState.COMPLETE, f.privacyStatus("event-1").cleanupState());
        assertNotNull(f.jdbc.queryForObject("SELECT payload_json FROM memory_message WHERE context_id=? AND source_event_id=?", String.class,
                MemoryArchiveStore.key(CONTEXT), "event-1"));
        var deletion = f.request(MemoryV2Contract.OPERATION_WITHDRAW,
                envelope -> new MemoryWithdrawalRequest(envelope, CONTEXT, "event-1", true));
        assertEquals(MemoryState.DELETION_PENDING, f.service.withdrawSource(deletion, f.sign(deletion)).state());
        assertNull(f.jdbc.queryForObject("SELECT payload_json FROM memory_message WHERE context_id=? AND source_event_id=?", String.class,
                MemoryArchiveStore.key(CONTEXT), "event-1"));
        new MemoryPrivacyCleanupWorker(f.store, f.transactions, List.of(), 4, 3, 60, 1, Clock.systemUTC()).runOnce();
        var repeated = f.request(MemoryV2Contract.OPERATION_WITHDRAW,
                envelope -> new MemoryWithdrawalRequest(envelope, CONTEXT, "event-1", false));
        assertEquals(MemoryState.DELETED, f.service.withdrawSource(repeated, f.sign(repeated)).state());
        assertEquals(MemoryPrivacyCleanupState.COMPLETE, f.privacyStatus("event-1").cleanupState());
    }

    @Test void sourcesAreRecheckedAfterCurrentAuthAndBeforeOutputEvenWhenVersionDidNotChange() throws Exception {
        Fixture f = new Fixture();
        var message = message(1, "must not escape");
        f.append(List.of(message));
        f.auth.onCheck = () -> {
            if (f.auth.calls % 2 == 0) f.jdbc.update("UPDATE memory_message SET state='WITHDRAWN'");
        };
        // Trigger a precise second check after the initial snapshot, without changing conversation revision.
        f.auth.calls = 0;
        code(MemoryErrorCode.VERSION_CONFLICT, f::read);
    }

    @Test void entityPagesPinConversationVersionAndRejectOldCursorAfterAppend() throws Exception {
        Fixture f = new Fixture();
        var message = message(1, "fact");
        f.append(List.of(message));
        List<MemoryEntityAnchor> entities = new ArrayList<>();
        for (int i = 1; i <= 3; i++) entities.add(new MemoryEntityAnchor("entity-" + i, CONTEXT, null,
                message.source().version(), List.of("alias-" + i), "definition", List.of(new MemorySourceDependency(message.source(), null))));
        var summary = summary(List.of(message), 1, List.of(), entities);
        var submit = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, summary));
        f.service.submitSummary(submit, f.sign(submit));
        var first = f.entities(null, 2);
        assertTrue(first.hasMore());
        assertEquals(1, f.entities(first.nextCursor(), 2).entities().size());
        f.append(List.of(message(2, "new input")));
        code(MemoryErrorCode.CURSOR_INVALID, () -> f.entities(first.nextCursor(), 2));
    }

    @Test void oversizedBatchAndMissingDatabaseFailWithoutAcknowledgingArchive() throws Exception {
        Fixture f = new Fixture();
        List<MemoryMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 40; i++) messages.add(message(i, "x".repeat(32768)));
        var permit = f.permit(messages);
        var delivery = f.delivery(permit.permitId(), messages);
        code(MemoryErrorCode.INVALID_ARGUMENT, () -> f.service.deliverMessages(delivery, f.signService(delivery)));
        assertEquals(0, f.count("memory_message"));
        f.jdbc.execute("DROP TABLE memory_message");
        var single = f.delivery(permit.permitId(), List.of(messages.get(0)));
        code(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.service.deliverMessages(single, f.signService(single)));
    }

    @Test void providerDoesNotPretendUnimplementedLearningSearchIsEmptySuccess() throws Exception {
        Fixture f = new Fixture();
        var sharing = new MemorySharingAccessService(f.security, new MemoryAccessPolicy(), f.sharing, f.sources, f.transactions);
        var provider = new MemoryV2RpcServiceImpl(f.service, sharing, f.security);
        var preference = f.request(MemoryV2Contract.OPERATION_SEARCH_PREFERENCES,
                envelope -> new MemoryPreferenceQueryRequest(envelope, CONTEXT, null, null, null, 10));
        code(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> provider.searchPreferences(preference, f.sign(preference)));
        var experience = f.request(MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES,
                envelope -> new MemoryExperienceQueryRequest(envelope, CONTEXT, null, null, 10));
        code(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> provider.searchExperiences(experience, f.sign(experience)));
    }

    @Test void datetimeAdaptersInterpretUtcLiteralsWithoutJvmTimezoneConversion() {
        var literal = java.time.LocalDateTime.parse("2026-10-19T12:34:56.123456");
        var expected = Instant.parse("2026-10-19T12:34:56.123456Z");
        assertEquals(expected, MemoryArchiveStore.utcInstant(literal));
        assertEquals(expected, MemoryArchiveStore.utcInstant(java.sql.Timestamp.valueOf(literal)));
    }

    @Test void missingMessageEventIdentityFailsClosedBeforeAnySourceLookup() throws Exception {
        Fixture f = new Fixture();
        var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, CONTEXT, "message-1", null, 1L, new MemoryRevision(1, ZERO, "message.v2"));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(f.transactions);
        code(MemoryErrorCode.FORBIDDEN, () -> transaction.execute(status -> {
            f.sources.requireCurrent(CONTEXT, List.of(new MemorySourceDependency(source, null)));
            return null;
        }));
    }

    @Test void maximumLengthEntityIdsRemainPageableWithOpaqueBoundedCursor() throws Exception {
        Fixture f = new Fixture();
        var message = message(1, "fact");
        f.append(List.of(message));
        var dependencies = List.of(new MemorySourceDependency(message.source(), null));
        var entities = List.of(new MemoryEntityAnchor("a".repeat(255), CONTEXT, null, message.source().version(), List.of("first"), "first", dependencies),
                new MemoryEntityAnchor("b".repeat(255), CONTEXT, null, message.source().version(), List.of("second"), "second", dependencies));
        var candidate = summary(List.of(message), 1, List.of(), entities);
        var request = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, candidate));
        f.service.submitSummary(request, f.sign(request));
        var first = f.entities(null, 1);
        assertTrue(first.nextCursor().length() < 100);
        assertEquals("b".repeat(255), f.entities(first.nextCursor(), 1).entities().get(0).entityId());
    }

    @Test void oversizedRestoredWindowReturnsBudgetFailureWithoutTruncatingArchive() throws Exception {
        Fixture f = new Fixture();
        for (int batch = 0; batch < 2; batch++) {
            List<MemoryMessage> messages = new ArrayList<>();
            for (int i = 1; i <= 20; i++) messages.add(message(batch * 20L + i, "x".repeat(32768)));
            f.append(messages);
        }
        code(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED, f::read);
        assertEquals(40, f.count("memory_message"));
        assertEquals(0, f.count("memory_summary"));
    }

    @Test void exactCompleteResultAnchorSurvivesColdReadAndRejectsForgedState() throws Exception {
        Fixture f = new Fixture();
        var call = build(1, MemoryMessage.Role.ASSISTANT, null, List.of(new MemoryToolCall("call", "draft", "draft", MemoryJsonValue.object(Map.of()))), List.of());
        var result = tool(2, MemoryToolResponse.ExecutionState.PARTIAL, "complete partial result", "call");
        var messages = List.of(call, result); f.append(messages);
        var base = summary(messages, 1, List.of(new MemoryCoveredRange(1, 2)), List.of());
        var forged = withAnchors(base, List.of(new MemoryToolResultAnchor(result.source(), "call", "draft", MemoryToolResponse.ExecutionState.RETURNED)));
        var invalid = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, forged));
        code(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED, () -> f.service.submitSummary(invalid, f.sign(invalid)));
        assertEquals(0, f.count("memory_summary"));
        var anchor = new MemoryToolResultAnchor(result.source(), "call", "draft", MemoryToolResponse.ExecutionState.PARTIAL);
        var valid = withAnchors(base, List.of(anchor));
        var submit = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, valid));
        f.service.submitSummary(submit, f.sign(submit)); f.restart();
        assertEquals(List.of(anchor), f.read().summaries().get(0).toolResults());
        var withdrawal = f.request(MemoryV2Contract.OPERATION_WITHDRAW, envelope -> new MemoryWithdrawalRequest(envelope, CONTEXT, result.source().sourceEventId(), false));
        f.service.withdrawSource(withdrawal, f.sign(withdrawal));
        assertTrue(f.read().summaries().isEmpty());
    }

    @Test void unchangedSignedEntityCanBePreservedInNextCumulativeSummary() throws Exception {
        Fixture f = new Fixture(); var first = message(1, "房源甲"); f.append(List.of(first));
        var base = summary(List.of(first), 1, List.of(), List.of(new MemoryEntityAnchor("entity", CONTEXT, null,
                new MemoryRevision(1, ZERO, "entity.v2.1"), List.of("房源甲"), "明确对象", List.of(new MemorySourceDependency(first.source(), null)))));
        var initial = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, base));
        f.service.submitSummary(initial, f.sign(initial)); var entity = f.entities(null, 10).entities().get(0);
        var second = message(2, "继续"); f.append(List.of(second));
        var next = summary(List.of(first, second), 3, List.of(), List.of(entity));
        next = resign(new MemorySummary("summary-2", CONTEXT, ASSOCIATION, next.version(), 3, null, next.coveredRange(), next.toolGroupRanges(), next.items(), next.entities(), next.dependencies()));
        var candidate = next;
        var submit = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, candidate));
        f.service.submitSummary(submit, f.sign(submit)); f.restart();
        assertEquals(List.of(entity), f.entities(null, 10).entities());
        assertEquals(List.of(entity), f.read().summaries().get(0).entities());
        assertEquals("summary-2", f.jdbc.queryForObject("SELECT summary_id FROM memory_entity_anchor", String.class));
    }

    @Test void forgedEntityHashIsRejectedEvenWhenSummaryHashIsValid() throws Exception {
        Fixture f = new Fixture(); var message = message(1, "fact"); f.append(List.of(message));
        var base = summary(List.of(message), 1, List.of(), List.of());
        var entity = new MemoryEntityAnchor("entity", CONTEXT, null, new MemoryRevision(1, ZERO, "entity.v2.1"), List.of("fact"), "fact", base.dependencies());
        var candidate = resign(new MemorySummary(base.summaryId(), CONTEXT, ASSOCIATION, base.version(), 1, null,
                base.coveredRange(), base.toolGroupRanges(), base.items(), List.of(entity), base.dependencies()));
        var submit = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, candidate));
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.service.submitSummary(submit, f.sign(submit)));
        assertEquals(0, f.count("memory_summary")); assertEquals(0, f.count("memory_entity_anchor"));
    }

    @Test void tamperedEntityDefinitionCannotEscapeThroughDetails() throws Exception {
        Fixture f = new Fixture(); var message = message(1, "fact"); f.append(List.of(message));
        var base = summary(List.of(message), 1, List.of(), List.of(new MemoryEntityAnchor("entity", CONTEXT, null,
                new MemoryRevision(1, ZERO, "entity.v2.1"), List.of("fact"), "original fact", List.of(new MemorySourceDependency(message.source(), null)))));
        var submit = f.request(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY, envelope -> new MemorySummaryRequest(envelope, base));
        f.service.submitSummary(submit, f.sign(submit)); var entity = base.entities().get(0);
        var tampered = new MemoryEntityAnchor(entity.entityId(), CONTEXT, null, entity.version(), entity.aliases(), "tampered fact", entity.dependencies());
        f.jdbc.update("UPDATE memory_entity_anchor SET payload_json=?", f.mapper.writeValueAsString(tampered));
        code(MemoryErrorCode.MEMORY_UNAVAILABLE, () -> f.entities(null, 10));
    }

    static MemorySummary withAnchors(MemorySummary summary, List<MemoryToolResultAnchor> anchors) {
        return resign(new MemorySummary(summary.summaryId(), summary.contextKey(), summary.association(), summary.version(), summary.expectedConversationRevision(), summary.runVersion(),
                summary.coveredRange(), summary.toolGroupRanges(), summary.items(), summary.entities(), anchors, summary.dependencies()));
    }
    static MemorySummary resign(MemorySummary summary) {
        return new MemorySummary(summary.summaryId(), summary.contextKey(), summary.association(),
                new MemoryRevision(summary.version().revision(), MemoryCanonicalHash.summaryHash(summary), summary.version().schemaVersion()),
                summary.expectedConversationRevision(), summary.runVersion(), summary.coveredRange(), summary.toolGroupRanges(), summary.items(), summary.entities(), summary.toolResults(), summary.dependencies());
    }

    static MemoryMessage message(long sequence, String text) { return build(sequence, MemoryMessage.Role.USER, text, List.of(), List.of()); }

    static MemoryMessage tool(long sequence, MemoryToolResponse.ExecutionState state, String text, String callId) {
        return build(sequence, MemoryMessage.Role.TOOL, null, List.of(), List.of(new MemoryToolResponse(callId, "draft", state, text,
                MemoryToolResponse.DataForm.INLINE_COMPLETE, MemoryJsonValue.object(Map.of("result", MemoryJsonValue.string(text))), null, List.of())));
    }

    static MemoryMessage build(long sequence, MemoryMessage.Role role, String text, List<MemoryToolCall> calls, List<MemoryToolResponse> responses) {
        String id = "message-" + sequence;
        var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, CONTEXT, id, "event-" + sequence, sequence, new MemoryRevision(1, ZERO, "message.v2"));
        var draft = new MemoryMessage(id, CONTEXT, sequence, ASSOCIATION, role, text, calls, responses, source);
        var actual = new MemorySourceRef(source.kind(), CONTEXT, id, source.sourceEventId(), sequence,
                new MemoryRevision(1, MemoryCanonicalHash.messageHash(draft), "message.v2"));
        return new MemoryMessage(id, CONTEXT, sequence, ASSOCIATION, role, text, calls, responses, actual);
    }

    static MemorySummary summary(List<MemoryMessage> messages, long expectedRevision, List<MemoryCoveredRange> groups, List<MemoryEntityAnchor> entities) {
        entities = entities.stream().map(entity -> new MemoryEntityAnchor(entity.entityId(), entity.contextKey(), entity.matterId(),
                new MemoryRevision(entity.version().revision(), MemoryCanonicalHash.entityHash(entity), entity.version().schemaVersion()),
                entity.aliases(), entity.briefDefinition(), entity.dependencies())).toList();
        var dependencies = messages.stream().map(message -> new MemorySourceDependency(message.source(), null)).toList();
        var items = List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT, "synthetic summary", dependencies));
        var draft = new MemorySummary("summary-1", CONTEXT, ASSOCIATION, new MemoryRevision(1, ZERO, "summary.v2"), expectedRevision, null,
                new MemoryCoveredRange(1, messages.size()), groups, items, entities, dependencies);
        return new MemorySummary(draft.summaryId(), CONTEXT, ASSOCIATION, new MemoryRevision(1, MemoryCanonicalHash.summaryHash(draft), "summary.v2"),
                expectedRevision, null, draft.coveredRange(), groups, items, entities, dependencies);
    }

    static void code(MemoryErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(MemoryException.class, action).code());
    }

    static class Fixture {
        final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        final DriverManagerDataSource datasource;
        final JdbcTemplate jdbc;
        final DataSourceTransactionManager transactions;
        final MemoryV2SecurityProperties properties = new MemoryV2SecurityProperties();
        final MemoryV2SecurityProperties.TrustedCaller caller = new MemoryV2SecurityProperties.TrustedCaller();
        final KeyPair identityPair;
        final AuthStub auth = new AuthStub();
        MemoryArchiveStore store;
        MemorySharingStore sharing;
        MemorySourceAuthorizer sources;
        MemoryV2RequestSecurity security;
        MemoryArchiveService service;
        MemoryExperienceService experiences;
        final MemoryExperienceProperties experienceProperties = new MemoryExperienceProperties();

        Fixture() throws Exception {
            identityPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            datasource = new DriverManagerDataSource("jdbc:h2:mem:archive-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
            jdbc = new JdbcTemplate(datasource);
            transactions = new DataSourceTransactionManager(datasource);
            try (var connection = datasource.getConnection()) {
                for (String file : List.of("20261019_memory_v2_request_nonce.sql", "20261019_memory_share_authorization.sql", "20261019_memory_v2_archive.sql", "20261019_memory_writer_fence.sql", "20261020_memory_preference_history.sql", "20261020_memory_preference_processing.sql", "20261021_memory_matter_lifecycle.sql", "20261021_memory_privacy_cleanup.sql", "20261021_memory_experience_records.sql", "20261021_memory_experience_processing.sql", "20261021_memory_domain_experience.sql")) {
                    String sql = Files.readString(Path.of(System.getProperty("maven.multiModuleProjectDirectory"), "sql/migrations", file))
                            .replace("USE bk_memory;", "").replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                            .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
                    if (file.equals("20261021_memory_experience_records.sql")) {
                        int start = sql.indexOf("SELECT COUNT(*) INTO @memory_experience_scope_index_count");
                        int end = sql.indexOf("DEALLOCATE PREPARE memory_experience_scope_index_stmt;", start);
                        if (start >= 0 && end >= 0) sql = sql.substring(0, start) + sql.substring(end + "DEALLOCATE PREPARE memory_experience_scope_index_stmt;".length());
                    }
                    ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
                    ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
                }
            }
            caller.setServiceDelivery(true); caller.setExperienceOutcomePublisher(true);
            caller.setPublicKeys(Map.of("fixture-key", com.bkanent.common.security.ServiceIdentityCrypto.encodePublicKey(identityPair.getPublic())));
            caller.setExperienceGovernancePublisher(true);
            caller.setAllowedAgentsByDomain(Map.of("marketing", Set.of("copy-agent")));
            properties.setTrustedCallers(Map.of(CALLER, caller, "other-service", caller));
            experienceProperties.setEnabled(true); experienceProperties.setProcessingEnabled(true);
            restart();
        }

        void restart() {
            store = new MemoryArchiveStore(jdbc, mapper);
            sharing = new MemorySharingStore(jdbc, mapper);
            sources = new MemorySourceAuthorizer(store, sharing, new MemoryAccessPolicy(), properties);
            security = new MemoryV2RequestSecurity(properties, auth, new MemoryV2ReplayGuard(jdbc));
            experiences = new MemoryExperienceService(store, security, sources, experienceProperties, properties, transactions);
            service = new MemoryArchiveService(store, security, sources, null, experiences, transactions);
        }

        MemoryArchiveService newService() { return new MemoryArchiveService(store, security, sources, transactions); }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        MemoryReceivePermitRequest permitRequest(List<MemoryMessage> messages, List<MemoryResultAdmission> admissions, String id) {
            var events = messages.stream().map(message -> new MemoryReceivePermitRequest.EventSubmission(message.source().sourceEventId(),
                    MemoryCanonicalHash.messageHash(message), "conversation-archive")).toList();
            return request(MemoryV2Contract.OPERATION_RECEIVE_PERMIT, CALLER, id,
                    envelope -> new MemoryReceivePermitRequest(envelope, CONTEXT, events, admissions));
        }
        MemoryReceivePermitResponse permit(List<MemoryMessage> messages) {
            var request = permitRequest(messages, List.of(), UUID.randomUUID().toString());
            return service.requestReceivePermit(request, sign(request));
        }
        MemoryDeliveryRequest delivery(String permit, List<MemoryMessage> messages) {
            return request(MemoryV2Contract.OPERATION_DELIVER_MESSAGES, envelope -> new MemoryDeliveryRequest(envelope, CONTEXT, ASSOCIATION, permit, messages));
        }
        void append(List<MemoryMessage> messages) {
            var permit = permit(messages);
            var delivery = delivery(permit.permitId(), messages);
            service.deliverMessages(delivery, sign(delivery));
        }
        MemoryWorkingSetResponse read() {
            var request = request(MemoryV2Contract.OPERATION_WORKING_SET, envelope -> new MemoryWorkingSetRequest(envelope, CONTEXT));
            return service.readWorkingSet(request, sign(request));
        }
        MemoryEntityQueryResponse entities(String cursor, int limit) {
            var request = request(MemoryV2Contract.OPERATION_QUERY_ENTITIES, envelope -> new MemoryEntityQueryRequest(envelope, CONTEXT, cursor, limit));
            return service.queryEntities(request, sign(request));
        }
        MemoryPrivacyCleanupStatusResponse privacyStatus(String eventId) {
            var request = request(MemoryV2Contract.OPERATION_PRIVACY_STATUS,
                    envelope -> new MemoryPrivacyCleanupStatusRequest(envelope, CONTEXT, eventId));
            return service.privacyCleanupStatus(request, sign(request));
        }
        <T extends MemoryV2Request> T request(String operation, Function<MemoryRequestEnvelope, T> factory) {
            return request(operation, CALLER, UUID.randomUUID().toString(), factory);
        }
        <T extends MemoryV2Request> T request(String operation, String caller, String id, Function<MemoryRequestEnvelope, T> factory) {
            var draft = factory.apply(new MemoryRequestEnvelope(operation, caller, MemoryV2Contract.MEMORY_AUDIENCE, id, ZERO, 2));
            return factory.apply(new MemoryRequestEnvelope(operation, caller, MemoryV2Contract.MEMORY_AUDIENCE, id, MemoryCanonicalHash.requestBodyHash(draft), 2));
        }
        MemoryRequestProof sign(MemoryV2Request request) {
            long now = Instant.now().getEpochSecond();
            return MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(42L), "fixture-key", now, now + 60,
                    UUID.randomUUID().toString().replace("-", ""), identityPair.getPrivate());
        }
        MemoryRequestProof signService(MemoryDeliveryRequest request) {
            long now = Instant.now().getEpochSecond();
            return MemoryRequestSigner.signServiceDelivery(request, "fixture-key", now, now + 60,
                    UUID.randomUUID().toString().replace("-", ""), identityPair.getPrivate());
        }
        MemoryRequestProof signService(MemoryExperienceOutcomeRequest request) {
            long now = Instant.now().getEpochSecond();
            return MemoryRequestSigner.signServiceExperienceOutcome(request, "fixture-key", now, now + 60,
                    UUID.randomUUID().toString().replace("-", ""), identityPair.getPrivate());
        }
    }

    static class AuthStub implements AuthPermissionRpcService {
        boolean allowed = true;
        long allowedOwner = 42L;
        int calls;
        String deniedPermission;
        Runnable onCheck = () -> { };
        public boolean validateToken(String token) { return false; }
        public AuthenticatedPrincipal resolvePrincipal(String token) { return null; }
        public boolean hasPermission(Long owner, String permission) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Auth must stay outside SQL locks");
            calls++; onCheck.run();
            return allowed && owner == allowedOwner && !java.util.Objects.equals(permission, deniedPermission);
        }
    }
}
