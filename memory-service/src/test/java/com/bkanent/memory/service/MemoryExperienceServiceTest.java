package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.bkanent.memory.service.MemoryArchiveServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryExperienceServiceTest {
    @Test void trustedOutcomeIsDurableIdempotentAndOnlyBecomesARevisionAfterWorkerProcessing() throws Exception {
        var matterFixture = new MemoryMatterServiceTest.MatterFixture();
        var start = matterFixture.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
        var matter = matterFixture.write(start).matter();
        var f = matterFixture.f;
        var outcome = outcome(f, matter.matterId(), "outcome-1", "keep the copy concise", MemoryExperience.Outcome.SUCCEEDED, 0);

        var accepted = f.experiences.submitOutcome(outcome, f.signService(outcome));
        assertEquals(MemoryExperienceEventStatusResponse.State.PENDING, accepted.state());
        assertEquals(0, f.count("memory_experience"), "intake does not block on or run the assessment");
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_processing_job WHERE job_kind='ASSESS_EXPERIENCE'", Integer.class));

        assertDoesNotThrow(() -> f.security.authorizeExperienceOutcome(outcome, f.signService(outcome)));
        assertEquals(accepted, f.experiences.submitOutcome(outcome, f.signService(outcome)));
        var conflict = outcome(f, matter.matterId(), "outcome-1", "different conclusion", MemoryExperience.Outcome.SUCCEEDED, 0);
        code(MemoryErrorCode.VERSION_CONFLICT, () -> f.experiences.submitOutcome(conflict, f.signService(conflict)));

        worker(f).runOnce();
        var status = status(f, "outcome-1");
        assertEquals(MemoryExperienceEventStatusResponse.State.SUCCEEDED, status.state());
        assertEquals(1, f.count("memory_experience"));
        assertEquals(1, f.count("memory_experience_revision"));
        assertEquals(1, f.count("memory_experience_evidence"));
        assertEquals(status, f.experiences.submitOutcome(outcome, f.signService(outcome)),
                "a retry after processing returns its durable receipt without creating another revision");
    }

    @Test void searchScoresAndBudgetsPrivateExperienceWithoutTurningReadsIntoEvidence() throws Exception {
        var matterFixture = new MemoryMatterServiceTest.MatterFixture();
        var start = matterFixture.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
        var matter = matterFixture.write(start).matter();
        var f = matterFixture.f;
        var outcome = outcome(f, matter.matterId(), "outcome-score", "预算说明保持简短", MemoryExperience.Outcome.SUCCEEDED, 0);
        f.experiences.submitOutcome(outcome, f.signService(outcome));
        worker(f).runOnce();
        assertEquals(MemoryExperienceEventStatusResponse.State.SUCCEEDED, status(f, "outcome-score").state());

        var limited = query(f, matter.matterId(), 1);
        assertTrue(limited.matches().isEmpty());
        assertTrue(limited.budgetLimited());
        var selected = query(f, matter.matterId(), 2048);
        assertEquals(1, selected.matches().size());
        var match = selected.matches().get(0);
        assertEquals(ExperienceSelectionPolicy.VERSION, selected.scoringVersion());
        assertEquals(.75, match.score().confidence());
        assertTrue(match.score().relevance() > 0);
        assertTrue(match.score().timeWeight() > 0 && match.score().timeWeight() <= 1);
        assertTrue(match.estimatedTokens() > 0);
        assertTrue(selected.usedTokens() <= 2048);
        assertEquals(matter.version().revision(), selected.matterRevision());

        var beforeObserved = f.jdbc.queryForObject("SELECT last_observed_at FROM memory_experience", Object.class);
        var sameMatterAgain = query(f, matter.matterId(), 2048);
        assertEquals(selected, sameMatterAgain, "the same matter reuses its validated selection snapshot");
        assertEquals(beforeObserved, f.jdbc.queryForObject("SELECT last_observed_at FROM memory_experience", Object.class));
        assertEquals(1, f.count("memory_experience_evidence"), "retrieval adds no evidence");
    }

    @Test void sameExperienceIdRemainsIsolatedAcrossPrivateOwners() throws Exception {
        var f = new Fixture();
        var secondOwner = new MemoryContextKey("84", CONTEXT.domainId(), CONTEXT.agentId(), "conversation-owner-84");
        var first = outcome(f, CONTEXT, null, "owner-42-outcome", "shared-local-id", "买房建议甲", MemoryExperience.Outcome.SUCCEEDED, 0);
        var second = outcome(f, secondOwner, null, "owner-84-outcome", "shared-local-id", "买房建议乙", MemoryExperience.Outcome.SUCCEEDED, 0);
        f.experiences.submitOutcome(first, f.signService(first));
        f.experiences.submitOutcome(second, f.signService(second));
        worker(f).runOnce();

        var firstResult = query(f, CONTEXT, null, 2048, 42);
        f.auth.allowedOwner = 84L;
        var secondResult = query(f, secondOwner, null, 2048, 84);
        assertEquals(List.of("买房建议甲"), firstResult.matches().stream().map(match -> match.experience().conclusion()).toList());
        assertEquals(List.of("买房建议乙"), secondResult.matches().stream().map(match -> match.experience().conclusion()).toList());
    }

    @Test void staleFeedbackCannotOverwriteARevisionCommittedByAnotherFeedback() throws Exception {
        var f = new Fixture();
        var initial = outcome(f, null, "outcome-stale-feedback", "预算建议", MemoryExperience.Outcome.SUCCEEDED, 0);
        f.experiences.submitOutcome(initial, f.signService(initial));
        worker(f).runOnce();

        var userMessage1 = message(1, "这条建议不适用于首付比例较低的情况");
        var userMessage2 = message(2, "这条建议仍适用于固定利率情况");
        f.append(List.of(userMessage1, userMessage2));
        var firstFeedback = feedback(f, "feedback-stale-1", initial, 1, MemoryExperience.Feedback.NEGATIVE, userMessage1);
        var secondFeedback = feedback(f, "feedback-stale-2", initial, 1, MemoryExperience.Feedback.POSITIVE, userMessage2);
        f.experiences.submitFeedback(firstFeedback, f.sign(firstFeedback));
        f.experiences.submitFeedback(secondFeedback, f.sign(secondFeedback));
        worker(f).runOnce();

        var states = List.of(status(f, "feedback-stale-1").state(), status(f, "feedback-stale-2").state());
        assertEquals(1, states.stream().filter(state -> state == MemoryExperienceEventStatusResponse.State.SUCCEEDED).count());
        assertEquals(1, states.stream().filter(state -> state == MemoryExperienceEventStatusResponse.State.FAILED).count());
        assertEquals(2L, f.jdbc.queryForObject("SELECT revision FROM memory_experience", Long.class));
        assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_experience_evidence WHERE experience_revision=2", Integer.class));
    }

    @Test void negativeOwnerFeedbackDeactivatesAndExplicitPositiveEvidenceCanReactivate() throws Exception {
        var matterFixture = new MemoryMatterServiceTest.MatterFixture();
        var start = matterFixture.event(new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房预算"));
        var matter = matterFixture.write(start).matter();
        var f = matterFixture.f;
        var outcome = outcome(f, matter.matterId(), "outcome-feedback", "预算建议有效", MemoryExperience.Outcome.SUCCEEDED, 0);
        f.experiences.submitOutcome(outcome, f.signService(outcome));
        worker(f).runOnce();

        var feedbackMessage = matterFixture.event(new MemoryMatterIntent(MemoryMatterIntent.Action.CONTINUE,
                matter.matterId(), matter.version().revision(), null));
        var feedbackSource = new MemorySourceDependency(feedbackMessage.source(), null);
        var correctedOutcome = outcome(f, matter.matterId(), "outcome-feedback", "预算建议有效", MemoryExperience.Outcome.SUCCEEDED, 0);
        var negativeOccurredAt = Instant.now();
        // Reuse the actual stable experience ID from the outcome request.
        var namedFeedback = f.request(MemoryV2Contract.OPERATION_EXPERIENCE_FEEDBACK, e -> new MemoryExperienceFeedbackRequest(e, CONTEXT,
                "feedback-negative", correctedOutcome.experienceId(), 1, new MemoryAssociation(matter.matterId(), null, null, null),
                MemoryExperience.Feedback.NEGATIVE, negativeOccurredAt, feedbackSource));
        f.experiences.submitFeedback(namedFeedback, f.sign(namedFeedback));
        worker(f).runOnce();
        var row = f.jdbc.queryForMap("SELECT revision,state,activation FROM memory_experience");
        assertEquals(2L, ((Number) row.get("revision")).longValue());
        assertEquals("INACTIVE", row.get("state"));
        assertEquals(0.0, ((Number) row.get("activation")).doubleValue());

        var reactivate = f.request(MemoryV2Contract.OPERATION_EXPERIENCE_LIFECYCLE, e -> new MemoryExperienceLifecycleRequest(e, CONTEXT,
                correctedOutcome.experienceId(), 2, MemoryExperienceLifecycleRequest.Action.REACTIVATE, "outcome-feedback", "owner rechecked positive result"));
        assertDoesNotThrow(() -> f.security.authorize(reactivate, f.sign(reactivate), MemoryV2Contract.OPERATION_EXPERIENCE_LIFECYCLE));
        var reactivated = f.experiences.changeLifecycle(reactivate, f.sign(reactivate));
        assertEquals(MemoryState.ACTIVE, reactivated.state());
        assertEquals(3, reactivated.revision());
        assertEquals("outcome-feedback", f.jdbc.queryForObject("SELECT evidence_id FROM memory_experience_evidence WHERE experience_revision=3 AND evidence_id='outcome-feedback'", String.class));
    }

    @Test void onlyExplicitlyRegisteredServiceCallersCanSubmitOutcomeAndServiceProofCannotSubmitFeedback() throws Exception {
        var f = new Fixture();
        var request = outcome(f, null, "outcome-auth", "verified result", MemoryExperience.Outcome.PARTIAL, 0);
        f.caller.setExperienceOutcomePublisher(false);
        assertEquals(request.envelope().bodyHash(), MemoryCanonicalHash.requestBodyHash(request));
        var serviceProof = assertDoesNotThrow(() -> f.signService(request));
        assertEquals(MemoryRequestProof.Actor.SERVICE, serviceProof.actor());
        assertTrue(java.security.MessageDigest.isEqual(f.secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                f.caller.getSigningSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> MemoryRequestSigner.verify(request, request.contextKey(), serviceProof, request.envelope().caller(),
                request.envelope().audience(), request.envelope().operation(), serviceProof.keyId(), Instant.now(),
                f.secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        code(MemoryErrorCode.FORBIDDEN, () -> f.experiences.submitOutcome(request, serviceProof));
        assertEquals(0, f.count("memory_experience_event"));

        f.caller.setExperienceOutcomePublisher(true);
        var sourceMessage = message(1, "my feedback");
        f.append(List.of(sourceMessage));
        var feedbackOccurredAt = Instant.now();
        var feedback = f.request(MemoryV2Contract.OPERATION_EXPERIENCE_FEEDBACK, e -> new MemoryExperienceFeedbackRequest(e, CONTEXT,
                "feedback-auth", "experience-auth", 1, ASSOCIATION, MemoryExperience.Feedback.POSITIVE, feedbackOccurredAt,
                new MemorySourceDependency(sourceMessage.source(), null)));
        var userProof = f.sign(feedback);
        var forged = new MemoryRequestProof(userProof.keyId(), userProof.issuedAtEpochSecond(), userProof.expiresAtEpochSecond(),
                userProof.nonce(), userProof.signature(), MemoryRequestProof.Actor.SERVICE);
        code(MemoryErrorCode.AUTHENTICATION_FAILED, () -> f.experiences.submitFeedback(feedback, forged));
    }

    @Test void outcomeRejectsCredentialTextAndFutureDatedEvidence() throws Exception {
        var f = new Fixture();
        var unsafe = outcome(f, null, "outcome-secret", "api_key=do-not-store-this", MemoryExperience.Outcome.SUCCEEDED, 0);
        f.caller.setExperienceOutcomePublisher(true);
        code(MemoryErrorCode.INVALID_EXTRACTION, () -> f.experiences.submitOutcome(unsafe, f.signService(unsafe)));
        var valid = outcome(f, null, "outcome-future", "valid conclusion", MemoryExperience.Outcome.SUCCEEDED, 0);
        var futureOccurredAt = Instant.now().plusSeconds(3600);
        var future = f.request(MemoryV2Contract.OPERATION_EXPERIENCE_OUTCOME, envelope -> new MemoryExperienceOutcomeRequest(envelope,
                valid.contextKey(), valid.evidenceId(), valid.experienceId(), valid.expectedRevision(), valid.association(),
                valid.scenario(), valid.applicability(), valid.conclusion(), valid.outcome(), futureOccurredAt, valid.source()));
        code(MemoryErrorCode.INVALID_ARGUMENT, () -> f.experiences.submitOutcome(future, f.signService(future)));
        assertEquals(0, f.count("memory_experience_event"));
    }

    @Test void feedbackCannotBorrowAnotherPrivateScopeMessageAsEvidence() throws Exception {
        var f = new Fixture();
        f.caller.setExperienceOutcomePublisher(true);
        var initial = outcome(f, null, "outcome-own-scope", "预算建议有效", MemoryExperience.Outcome.SUCCEEDED, 0);
        f.experiences.submitOutcome(initial, f.signService(initial));
        worker(f).runOnce();
        var foreignContext = new MemoryContextKey("84", CONTEXT.domainId(), CONTEXT.agentId(), "someone-elses-conversation");
        var foreignSource = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, foreignContext, "foreign-message", "foreign-event", 1L,
                new MemoryRevision(1, "b".repeat(64), "message.v2"));
        var rejected = assertThrows(MemoryException.class, () -> new MemoryExperienceFeedbackRequest(
                new MemoryRequestEnvelope(MemoryV2Contract.OPERATION_EXPERIENCE_FEEDBACK, CALLER, MemoryV2Contract.MEMORY_AUDIENCE,
                        "feedback-foreign", "0".repeat(64), MemoryV2Contract.VERSION),
                CONTEXT, "feedback-foreign", initial.experienceId(), 1, initial.association(),
                MemoryExperience.Feedback.POSITIVE, Instant.now(), new MemorySourceDependency(foreignSource, null)));
        assertEquals(MemoryErrorCode.INVALID_ARGUMENT, rejected.code());
        assertEquals(1, f.count("memory_experience_evidence"));
    }

    @Test void deletingOutcomeSourceImmediatelyTombstonesDerivedExperienceAndPreventsLateUse() throws Exception {
        var f = new Fixture();
        var outcome = outcome(f, null, "outcome-delete", "private lesson", MemoryExperience.Outcome.SUCCEEDED, 0);
        f.experiences.submitOutcome(outcome, f.signService(outcome));
        worker(f).runOnce();
        assertEquals(MemoryExperienceEventStatusResponse.State.SUCCEEDED, status(f, "outcome-delete").state());

        var withdraw = f.request(MemoryV2Contract.OPERATION_WITHDRAW,
                e -> new MemoryWithdrawalRequest(e, CONTEXT, "outcome-delete", true));
        var receipt = f.service.withdrawSource(withdraw, f.sign(withdraw));
        assertEquals(MemoryState.DELETED, receipt.state());
        assertEquals("DELETED", f.jdbc.queryForObject("SELECT state FROM memory_experience", String.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_experience_revision WHERE payload_json IS NOT NULL", Integer.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM memory_experience_event WHERE request_json IS NOT NULL", Integer.class));
        assertEquals(MemoryExperienceEventStatusResponse.State.DELETED, status(f, "outcome-delete").state());
    }

    private static MemoryExperienceProcessingWorker worker(MemoryArchiveServiceTest.Fixture f) {
        return new MemoryExperienceProcessingWorker(f.store, f.experiences, f.experienceProperties, f.transactions);
    }

    private static MemoryExperienceEventStatusResponse status(MemoryArchiveServiceTest.Fixture f, String id) {
        var request = f.request(MemoryV2Contract.OPERATION_EXPERIENCE_STATUS,
                e -> new MemoryExperienceEventStatusRequest(e, CONTEXT, id));
        return f.experiences.status(request, f.sign(request));
    }

    private static MemoryExperienceQueryResponse query(MemoryArchiveServiceTest.Fixture f, String matterId, int budget) {
        return query(f, CONTEXT, matterId, budget, 42);
    }

    private static MemoryExperienceQueryResponse query(MemoryArchiveServiceTest.Fixture f, MemoryContextKey context,
            String matterId, int budget, long principal) {
        var request = f.request(MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES,
                e -> new MemoryExperienceQueryRequest(e, context, null, null, 20, null, matterId, budget));
        var proof = principal == 42 ? f.sign(request) : signAs(f, request, principal);
        return f.experiences.search(request, proof);
    }

    private static MemoryExperienceOutcomeRequest outcome(MemoryArchiveServiceTest.Fixture f, String matterId, String eventId,
            String conclusion, MemoryExperience.Outcome outcome, long expectedRevision) {
        return outcome(f, CONTEXT, matterId, eventId, "experience-" + eventId, conclusion, outcome, expectedRevision);
    }

    private static MemoryExperienceOutcomeRequest outcome(MemoryArchiveServiceTest.Fixture f, MemoryContextKey context,
            String matterId, String eventId, String experienceId, String conclusion, MemoryExperience.Outcome outcome, long expectedRevision) {
        var occurredAt = Instant.now();
        var source = new MemorySourceDependency(new MemorySourceRef(MemorySourceRef.Kind.OUTCOME, context,
                "domain-result-" + eventId, eventId, null,
                new MemoryRevision(1, "a".repeat(64), "domain-outcome.v1")), null);
        var association = new MemoryAssociation(matterId, null, null, null);
        return f.request(MemoryV2Contract.OPERATION_EXPERIENCE_OUTCOME, envelope -> new MemoryExperienceOutcomeRequest(envelope,
                context, eventId, experienceId, expectedRevision, association,
                "买房预算场景", "用户询问预算与价格", conclusion, outcome, occurredAt, source));
    }

    private static MemoryExperienceFeedbackRequest feedback(MemoryArchiveServiceTest.Fixture f, String evidenceId,
            MemoryExperienceOutcomeRequest experience, long revision, MemoryExperience.Feedback value, MemoryMessage source) {
        var occurredAt = Instant.now();
        return f.request(MemoryV2Contract.OPERATION_EXPERIENCE_FEEDBACK, envelope -> new MemoryExperienceFeedbackRequest(envelope,
                experience.contextKey(), evidenceId, experience.experienceId(), revision, experience.association(), value, occurredAt,
                new MemorySourceDependency(source.source(), null)));
    }

    private static MemoryRequestProof signAs(MemoryArchiveServiceTest.Fixture f, MemoryV2Request request, long ownerId) {
        long now = Instant.now().getEpochSecond();
        return MemoryRequestSigner.sign(request, new com.bkanent.common.rpc.AuthenticatedPrincipal(ownerId), "fixture-key",
                now, now + 60, UUID.randomUUID().toString().replace("-", ""), f.secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void code(MemoryErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(MemoryException.class, action).code());
    }
}
