package com.bkanent.common.memory;

import com.bkanent.common.serialize.Fastjson2ConstructorSerialization;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Offline v2 RPC contract tests only: shape, binding, serialization and version rejection. */
class MemoryV2RpcContractTest {
    private static final String HASH = "a".repeat(64);
    private static final Instant AT = Instant.parse("2026-10-18T00:00:00Z");
    private static final MemoryContextKey CONTEXT =
            new MemoryContextKey("owner-1", "marketing", "copy-agent", "conversation-1");
    private static final MemoryAssociation ASSOCIATION = new MemoryAssociation("matter-1", "run-1", "a2a-1",
            new MemoryAssociation.DomainSessionRef("marketing", "domain-session-1"));
    private final Fastjson2ConstructorSerialization serialization = new Fastjson2ConstructorSerialization();

    private MemoryRequestEnvelope envelope(String operation) {
        return new MemoryRequestEnvelope(operation, "agent-service", "memory-service", "req-1", HASH,
                MemoryV2Contract.VERSION);
    }

    @Test void envelopeKeepsRequestHashIdentityAcrossWireSerialization() throws Exception {
        MemoryRequestEnvelope envelope = envelope(MemoryV2Contract.OPERATION_WORKING_SET);
        assertThat(envelope.requestIdentity()).isEqualTo("req-1:" + HASH);
        assertThat(spi(envelope, MemoryRequestEnvelope.class)).isEqualTo(envelope);
    }

    @Test void unsupportedContractVersionsAreRejectedAtConstruction() {
        for (int version : new int[]{0, 1, 3}) {
            assertThatThrownBy(() -> new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_WORKING_SET,
                    "agent-service", "memory-service", "req-1", HASH, version))
                    .isInstanceOf(MemoryException.class).hasMessage("UNSUPPORTED_VERSION (VERSION)");
        }
    }

    @Test void envelopeRejectsUnknownOperationWrongAudienceAndInvalidHashIdentity() {
        assertThatThrownBy(() -> envelope("memory.unknown.operation"))
                .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (IDENTITY)");
        assertThatThrownBy(() -> new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_WORKING_SET,
                "agent-service", "rag-service", "req-1", HASH, MemoryV2Contract.VERSION))
                .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (IDENTITY)");
        for (String hash : new String[]{null, "not-a-hash", "A".repeat(64), "a".repeat(63)}) {
            assertThatThrownBy(() -> new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_WORKING_SET,
                    "agent-service", "memory-service", "req-1", hash, MemoryV2Contract.VERSION))
                    .isInstanceOf(MemoryException.class);
        }
        assertThatThrownBy(() -> new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_WORKING_SET,
                "bad caller", "memory-service", "req-1", HASH, MemoryV2Contract.VERSION))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_WORKING_SET,
                "agent-service", "memory-service", "bad request", HASH, MemoryV2Contract.VERSION))
                .isInstanceOf(MemoryException.class);
    }

    @Test void everyRequestBindsItsDeclaredOperationAndRoundTrips() throws Exception {
        MemorySummary summary = summary();
        var dependency = new MemorySourceDependency(source("origin-1", 1), null);
        MemoryMessage message = new MemoryMessage("message-1", CONTEXT, 1, ASSOCIATION,
                MemoryMessage.Role.USER, "hi", List.of(), List.of(), source("message-1", 1), List.of(dependency));
        MemoryMatter matter = new MemoryMatter("matter-1", CONTEXT, version(1), "write copy",
                MemoryMatter.State.ACTIVE, null, List.of(new MemorySourceDependency(source("confirmation", 1), null)));
        List<Object> requests = List.of(
                new MemoryMatterWriteRequest(envelope(MemoryV2Contract.OPERATION_MATTER_WRITE), CONTEXT, new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "write copy"), dependency, 0, new MemoryWriteAuthority("run-1", 1)),
                new MemoryWorkingSetRequest(envelope(MemoryV2Contract.OPERATION_WORKING_SET), CONTEXT),
                new MemoryReceivePermitRequest(envelope(MemoryV2Contract.OPERATION_RECEIVE_PERMIT), CONTEXT,
                        List.of(new MemoryReceivePermitRequest.EventSubmission("event-1", HASH, "conversation archive"))),
                new MemoryDeliveryRequest(envelope(MemoryV2Contract.OPERATION_DELIVER_MESSAGES), CONTEXT,
                        ASSOCIATION, "permit-1", List.of(message)),
                new MemorySummaryRequest(envelope(MemoryV2Contract.OPERATION_SUBMIT_SUMMARY), summary),
                new MemoryMatterQueryRequest(envelope(MemoryV2Contract.OPERATION_QUERY_MATTERS), CONTEXT, null, 50),
                new MemoryEntityQueryRequest(envelope(MemoryV2Contract.OPERATION_QUERY_ENTITIES), CONTEXT, "cursor-1", 500),
                new MemoryPreferenceQueryRequest(envelope(MemoryV2Contract.OPERATION_SEARCH_PREFERENCES), CONTEXT,
                        "subject-1", "copy_length", null, 20),
                new MemoryExperienceQueryRequest(envelope(MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES), CONTEXT,
                        "copy-draft", null, 20),
                new MemoryCollaborationSearchRequest(envelope(MemoryV2Contract.OPERATION_SEARCH_COLLABORATION), CONTEXT,
                        "matter-1", "agent-handoff", "approved result", 10),
                new MemoryExperienceOutcomeRequest(envelope(MemoryV2Contract.OPERATION_EXPERIENCE_OUTCOME), CONTEXT,
                        "outcome-1", "experience-1", 0, ASSOCIATION, "buyer request", "short copy", "keep concise",
                        MemoryExperience.Outcome.SUCCEEDED, AT,
                        new MemorySourceDependency(new MemorySourceRef(MemorySourceRef.Kind.OUTCOME, CONTEXT,
                                "domain-result-1", "outcome-1", null, version(1)), null)),
                new MemoryExperienceFeedbackRequest(envelope(MemoryV2Contract.OPERATION_EXPERIENCE_FEEDBACK), CONTEXT,
                        "feedback-1", "experience-1", 1, ASSOCIATION, MemoryExperience.Feedback.NEGATIVE, AT,
                        new MemorySourceDependency(source("feedback-message", 1), null)),
                new MemoryExperienceEventStatusRequest(envelope(MemoryV2Contract.OPERATION_EXPERIENCE_STATUS), CONTEXT, "outcome-1"),
                new MemoryExperienceLifecycleRequest(envelope(MemoryV2Contract.OPERATION_EXPERIENCE_LIFECYCLE), CONTEXT,
                        "experience-1", 1, MemoryExperienceLifecycleRequest.Action.DEACTIVATE, null, "owner requested deactivation"),
                new MemoryDomainExperienceManagementRequest(envelope(MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_MANAGE),
                        CONTEXT, "dge_guidance-1", "copy-agent", 0, MemoryDomainExperienceManagementRequest.Action.PUBLISH,
                        "gov_approval-1", "policy-v1", "review_source-1", "domain-learning", "copy-draft",
                        "Concise property copy", "When facts have been verified", "State verified facts first",
                        "Governance reviewed aggregate evidence", AT.plusSeconds(86400), true, true, null),
                new MemoryDomainExperienceQueryRequest(envelope(MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_SEARCH), CONTEXT,
                        "copy-draft", null, 20),
                new MemoryDomainExperienceUseCheckRequest(envelope(MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_CHECK), CONTEXT,
                        "dge_guidance-1", 1, HASH),
                new MemoryGrantRequest(envelope(MemoryV2Contract.OPERATION_GET_GRANT), CONTEXT,
                        new MemoryExportRef("export-1", CONTEXT, version(1)),
                        new MemoryScope(MemoryScope.Kind.TASK_SHARED, null, null, "shared-1"), "matter-1", "collaboration"),
                new MemoryWithdrawalRequest(envelope(MemoryV2Contract.OPERATION_WITHDRAW), CONTEXT, "event-1", false),
                new MemoryPrivacyCleanupStatusRequest(envelope(MemoryV2Contract.OPERATION_PRIVACY_STATUS), CONTEXT, "event-1"),
                new MemoryWriterRequest(envelope(MemoryV2Contract.OPERATION_WRITER), CONTEXT,
                        MemoryWriterRequest.Action.ACQUIRE, "run-1", 0),
                new MemoryBoundaryRequest(envelope(MemoryV2Contract.OPERATION_BOUNDARY), CONTEXT,
                        new MemoryConversationVersion(1, 1, "policy-v2"), List.of(dependency),
                        List.of(new MemoryPreferenceSelectionRef("p-block", 1, HASH, 1, "matter-1", 1, HASH)),
                        List.of(new MemoryExperienceSelectionRef("experience-1", version(2), "matter-1", 1, HASH,
                                "experience-selection-v1"))),
                new MemoryPreferenceWriteRequest(envelope(MemoryV2Contract.OPERATION_PREFERENCE_WRITE), CONTEXT, 0,
                        new MemoryPreferenceAssessment("budget", PreferenceTransition.Assessment.PREFERENCE, PreferenceTransition.Change.OBSERVATION,
                                PreferenceTransition.Polarity.NEGATIVE, MemoryJsonValue.number("100"), null, null, null, null,
                                List.of(), .8, "extract-v1", new MemorySourceDependency(source("user", 2), null))),
                new MemoryPreferenceDetailRequest(envelope(MemoryV2Contract.OPERATION_PREFERENCE_DETAIL), CONTEXT, "block", null, 20, AT, null),
                new MemoryPreferenceAssemblyRequest(envelope(MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES), CONTEXT, "matter-1", 800));
        for (Object request : requests) {
            assertThat(spi(request, request.getClass())).isEqualTo(request);
        }
        assertThatThrownBy(() -> new MemoryWorkingSetRequest(
                envelope(MemoryV2Contract.OPERATION_DELIVER_MESSAGES), CONTEXT))
                .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (IDENTITY)");
        assertThatThrownBy(() -> new MemorySummaryRequest(null, summary))
                .isInstanceOf(MemoryException.class);
    }

    @Test void responsesRoundTripAndPagesNeverClaimMoreWithoutCursor() throws Exception {
        MemoryConversationVersion conversation = new MemoryConversationVersion(5, 3, "policy-v2");
        List<Object> responses = List.of(
                new MemoryMatterWriteResponse(new MemoryMatter("matter", CONTEXT, version(1), "write copy", MemoryMatter.State.ACTIVE, null, List.of(new MemorySourceDependency(source("confirmation", 1), null))), conversation, true),
                new MemoryWorkingSetResponse(CONTEXT, conversation, List.of(), List.of(), List.of()),
                new MemoryReceivePermitResponse("permit-1",
                        List.of(new MemoryReceivePermitResponse.EventAcceptance("event-1", true, null))),
                new MemoryDeliveryResponse("permit-1", conversation,
                        List.of(new MemoryDeliveryResponse.DeliveryResult(1, true, null))),
                new MemorySummaryResponse("summary-1", true, null, conversation),
                new MemoryMatterQueryResponse(List.of(), null, false),
                new MemoryEntityQueryResponse(List.of(), "next-1", true),
                new MemoryPreferenceQueryResponse(List.of(), "next-1", true),
                new MemoryExperienceQueryResponse(List.of(), null, false),
                new MemoryExperienceEventStatusResponse("event-1", MemoryExperienceEventStatusResponse.State.PENDING, 0, null, AT, AT),
                new MemoryExperienceLifecycleResponse("experience-1", 2, MemoryState.INACTIVE, "manual deactivation"),
                new MemoryGrantResponse(grant(), List.of()),
                new MemoryWithdrawalResponse("event-1", MemoryState.DELETION_PENDING, conversation),
                new MemoryPrivacyCleanupStatusResponse(CONTEXT, "event-1", MemoryState.DELETION_PENDING,
                        MemoryPrivacyCleanupState.RETRY, 2, "CLEANUP_FAILED", AT, null),
                new MemoryWriterResponse(new MemoryWriteAuthority("run-1", 1), 1_798_000_000_000L, conversation),
                new MemoryBoundaryResponse(conversation, true),
                new MemoryPreferenceWriteResponse("block", 1, "transition", true),
                new MemoryPreferenceAssemblyResponse("matter-1", 1, HASH, AT, "preference-selection-v1", List.of(), false),
                new MemoryDomainExperienceManagementResponse("dge_guidance-1", 1,
                        MemoryDomainExperienceManagementResponse.State.PUBLISHED, HASH),
                new MemoryDomainExperienceQueryResponse(List.of(new MemoryDomainExperience("dge_guidance-1", "marketing",
                        "copy-agent", 1, "domain-learning", "copy-draft", "Concise property copy",
                        "When facts have been verified", "State verified facts first", "Governance reviewed aggregate evidence",
                        "policy-v1", HASH, AT.plusSeconds(86400), AT)), null, false, AT),
                new MemoryDomainExperienceUseCheckResponse("dge_guidance-1", 1, 1,
                        MemoryDomainExperienceUseCheckResponse.State.CURRENT, AT),
                new MemoryCollaborationSearchResponse(List.of(new MemoryCollaborationMatch(
                        new MemoryExportRef("export-1", CONTEXT, version(1)), "approved result", .9)), AT, 15, false));
        for (Object response : responses) {
            assertThat(spi(response, response.getClass())).isEqualTo(response);
        }
        assertThatThrownBy(() -> new MemoryMatterQueryResponse(List.of(), null, true))
                .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (CURSOR)");
        assertThatThrownBy(() -> new MemoryCollaborationSearchResponse(List.of(new MemoryCollaborationMatch(
                new MemoryExportRef("export-1", CONTEXT, version(1)), "approved result", .9)), AT, 14, false))
                .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (PAGE)");
    }

    @Test void sharedRequestKeepsReceivingContextSeparateFromTheSourceAcrossWire() throws Exception {
        MemoryContextKey recipient = new MemoryContextKey("owner-1", "supervisor", "supervisor-agent", "recipient-session");
        MemoryScope scope = new MemoryScope(MemoryScope.Kind.TASK_SHARED, null, null, "shared-1");
        MemoryGrantRequest request = new MemoryGrantRequest(envelope(MemoryV2Contract.OPERATION_GET_GRANT), recipient,
                new MemoryExportRef("export-1", CONTEXT, version(1)), scope, "matter-1", "collaboration");

        MemoryGrantRequest decoded = (MemoryGrantRequest) spi(request, MemoryGrantRequest.class);
        assertThat(decoded.contextKey()).isEqualTo(recipient);
        assertThat(decoded.sharedRef().sourceContext()).isEqualTo(CONTEXT);
        assertThat(decoded).isEqualTo(request);
        MemoryGrantRequest anotherSource = new MemoryGrantRequest(request.envelope(), recipient,
                new MemoryExportRef("export-1", new MemoryContextKey(CONTEXT.ownerId(), CONTEXT.domainId(), CONTEXT.agentId(), "other-session"), version(1)),
                scope, "matter-1", "collaboration");
        assertThat(MemoryCanonicalHash.requestBodyHash(anotherSource)).isNotEqualTo(MemoryCanonicalHash.requestBodyHash(request));
    }

    @Test void sharedRequestAndParticipantsRequireAnExplicitReceivingContext() {
        MemoryScope scope = new MemoryScope(MemoryScope.Kind.SESSION_SHARED, null, null, "shared-1");
        assertThatThrownBy(() -> new MemoryGrantRequest(envelope(MemoryV2Contract.OPERATION_GET_GRANT), null,
                new MemoryExportRef("export-1", CONTEXT, version(1)), scope, null, "collaboration"))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryShareGrant.Participant("supervisor", "supervisor-agent", null))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryGrantRequest(envelope(MemoryV2Contract.OPERATION_GET_GRANT), CONTEXT,
                new MemoryExportRef("export-1", CONTEXT, version(1)), MemoryScope.privateScope(CONTEXT.domainId(), CONTEXT.agentId()), null, "collaboration"))
                .isInstanceOf(MemoryException.class);
    }

    @Test void collaborationSearchContractsRedactTextAndEnforceResponseBounds() {
        var request = new MemoryCollaborationSearchRequest(envelope(MemoryV2Contract.OPERATION_SEARCH_COLLABORATION),
                CONTEXT, "matter-1", "agent-handoff", "private query text", 1);
        var match = new MemoryCollaborationMatch(new MemoryExportRef("export-1", CONTEXT, version(1)),
                "private response text", .8);
        assertThat(request.toString()).doesNotContain("private query text");
        assertThat(match.toString()).doesNotContain("private response text");

        List<MemoryCollaborationMatch> tooMany = java.util.stream.IntStream.range(0,
                MemoryCollaborationSearchResponse.MAX_MATCHES + 1)
                .mapToObj(index -> new MemoryCollaborationMatch(new MemoryExportRef("export-" + index, CONTEXT, version(1)), "x", .5))
                .toList();
        assertThatThrownBy(() -> new MemoryCollaborationSearchResponse(tooMany, AT, tooMany.size(), false))
                .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (PAGE)");
    }

    @Test void permitEventsAndDeliveryResultsPinExactHashAndSequence() {
        assertThatThrownBy(() -> new MemoryReceivePermitRequest.EventSubmission("event-1", "not-a-hash", "archive"))
                .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (HASH)");
        assertThatThrownBy(() -> new MemoryDeliveryResponse.DeliveryResult(0, true, null))
                .isInstanceOf(MemoryException.class);
        assertThatThrownBy(() -> new MemoryReceivePermitRequest(envelope(MemoryV2Contract.OPERATION_RECEIVE_PERMIT),
                CONTEXT, List.of()))
                .isInstanceOf(MemoryException.class);
        for (int limit : new int[]{0, 501}) {
            assertThatThrownBy(() -> new MemoryMatterQueryRequest(
                    envelope(MemoryV2Contract.OPERATION_QUERY_MATTERS), CONTEXT, null, limit))
                    .isInstanceOf(MemoryException.class).hasMessage("INVALID_ARGUMENT (PAGE)");
        }
    }

    @Test void admissionWithdrawalAndActorProofRoundTripThroughTheChosenWire() throws Exception {
        var admission = new MemoryResultAdmission(source("accepted", 1), "call-1", "result-event", "result-message", 2);
        var request = new MemoryReceivePermitRequest(envelope(MemoryV2Contract.OPERATION_RECEIVE_PERMIT), CONTEXT,
                List.of(), List.of(admission));
        assertThat(spi(request, MemoryReceivePermitRequest.class)).isEqualTo(request);
        var withdrawal = new MemoryWithdrawalRequest(envelope(MemoryV2Contract.OPERATION_WITHDRAW), CONTEXT, "event-1", true);
        assertThat(spi(withdrawal, MemoryWithdrawalRequest.class)).isEqualTo(withdrawal);
        var response = new MemoryWithdrawalResponse("event-1", MemoryState.DELETION_PENDING, new MemoryConversationVersion(2, 1, "v2"));
        assertThat(spi(response, MemoryWithdrawalResponse.class)).isEqualTo(response);
        var status = new MemoryPrivacyCleanupStatusResponse(CONTEXT, "event-1", MemoryState.DELETED,
                MemoryPrivacyCleanupState.COMPLETE, 2, null, AT, AT.plusSeconds(1));
        assertThat(spi(status, MemoryPrivacyCleanupStatusResponse.class)).isEqualTo(status);
        assertThat(MemoryV2Contract.DUBBO_SERVICE_VERSION).isEqualTo("2.3.0");
        var proof = new MemoryRequestProof("key", 100, 160, "n".repeat(22), "s".repeat(43), MemoryRequestProof.Actor.SERVICE);
        assertThat(spi(proof, MemoryRequestProof.class)).isEqualTo(proof);
    }

    @Test void idsMatchSqlMaximumRatherThanAcceptingA256thCharacter() {
        assertThat(new MemoryContextKey("o".repeat(255), "d", "a", "c").ownerId()).hasSize(255);
        assertThatThrownBy(() -> new MemoryContextKey("o".repeat(256), "d", "a", "c")).isInstanceOf(MemoryException.class);
    }

    private Object spi(Object value, Class<?> type) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = serialization.serialize(null, bytes);
        output.writeObject(value);
        output.flushBuffer();
        return serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray())).readObject(type);
    }

    private MemoryRevision version(long revision) { return new MemoryRevision(revision, "a".repeat(64), "memory-v2"); }
    private MemorySourceRef source(String id, long sequence) {
        return new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, CONTEXT, id, "event-" + id, sequence, version(1));
    }
    private MemorySummary summary() {
        MemorySourceDependency dependency = new MemorySourceDependency(source("input", 1), new MemoryGrantRef("grant-1", 3));
        return new MemorySummary("summary-1", CONTEXT, ASSOCIATION, version(1), 5, new MemoryRunVersion("run-1", 9),
                new MemoryCoveredRange(1, 40), List.of(new MemoryCoveredRange(1, 40)),
                List.of(new MemorySummary.Item(MemorySummary.ItemKind.NEGATION, "not a permanent default", List.of(dependency))),
                List.of(), List.of(dependency));
    }
    private MemoryShareGrant grant() {
        return new MemoryShareGrant(new MemoryGrantRef("grant-1", 3), CONTEXT,
                new MemoryScope(MemoryScope.Kind.TASK_SHARED, null, null, "group-1"), "matter-1", "reuse",
                List.of(new MemoryShareGrant.Participant("supervisor", "supervisor-agent", "supervisor-conversation")),
                List.of(MemoryShareGrant.Projection.DURABLE_EXPORT), List.of(source("result", 40)),
                AT, AT.plusSeconds(3600), MemoryShareGrant.State.ACTIVE);
    }
}
