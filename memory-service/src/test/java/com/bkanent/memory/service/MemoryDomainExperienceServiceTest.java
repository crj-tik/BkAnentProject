package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.bkanent.memory.service.MemoryArchiveServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryDomainExperienceServiceTest {
    private static final String GOVERNANCE_PERMISSION = "memory.experience.governance.publish";

    @Test
    void onlyAuthorizedApprovalCreatesAnIndependentDomainRecordAndRetryIsIdempotent() throws Exception {
        var f = new MemoryArchiveServiceTest.Fixture();
        var request = publish(f, "publish-independent-1", 0, "事实先核验，再简洁说明房源信息");

        var response = stage("publish", () -> f.experiences.manageDomainExperience(request, f.sign(request)));
        assertEquals(MemoryDomainExperienceManagementResponse.State.PUBLISHED, response.state());
        assertEquals(1, response.revision());
        assertEquals(response, f.experiences.manageDomainExperience(request, f.sign(request)));
        assertEquals(1, f.count("memory_domain_experience"));
        assertEquals(1, f.count("memory_domain_experience_revision"));
        assertEquals(1, f.count("memory_domain_experience_event"));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='MEMORY_DOMAIN_EXPERIENCE' "
                + "AND column_name IN ('OWNER_ID','EXPERIENCE_ID','SOURCE_ID','SOURCE_EVENT_ID','GRANT_ID')", Integer.class));
    }

    @Test
    void readersSeeOnlyTheExactApprovedDomainAndAgentWithoutPrivateEvidenceFields() throws Exception {
        var f = new MemoryArchiveServiceTest.Fixture();
        var publish = publish(f, "publish-reader-1", 0, "核验后陈述明确的交易条件");
        stage("publish for owner test", () -> f.experiences.manageDomainExperience(publish, f.sign(publish)));

        f.auth.allowedOwner = 84L;
        var otherOwner = new MemoryContextKey("84", CONTEXT.domainId(), CONTEXT.agentId(), "other-owner-session");
        var query = query(f, "read-as-other-owner", otherOwner, null);
        var result = stage("search as second owner", () -> f.experiences.searchDomainExperiences(query, signAs(f, query, 84L)));
        assertEquals(1, result.experiences().size());
        var aggregate = result.experiences().get(0);
        assertEquals("dge_guidance-1", aggregate.aggregateId());
        assertEquals(CONTEXT.domainId(), aggregate.domainId());
        assertEquals(CONTEXT.agentId(), aggregate.agentId());
        assertEquals(MemoryDomainExperienceUseCheckResponse.State.CURRENT,
                checkUse(f, "check-as-other-owner", otherOwner, aggregate).state());
        assertFalse(f.mapper.writeValueAsString(aggregate).contains("ownerId"));
        assertFalse(f.mapper.writeValueAsString(aggregate).contains("sourceReviewRef"));
        assertFalse(f.mapper.writeValueAsString(aggregate).contains("governanceApprovalId"));

        var foreign = query(f, "read-foreign-agent", new MemoryContextKey("84", "marketing", "listing-agent", "foreign-agent-session"), null);
        code(MemoryErrorCode.FORBIDDEN, () -> f.experiences.searchDomainExperiences(foreign, signAs(f, foreign, 84L)));
    }

    @Test
    void publishingRequiresTheGovernanceCallerFlagAndCurrentGovernancePermission() throws Exception {
        var f = new MemoryArchiveServiceTest.Fixture();
        var request = publish(f, "publish-not-governor", 0, "只发布独立归纳");
        f.caller.setExperienceGovernancePublisher(false);
        code(MemoryErrorCode.FORBIDDEN, () -> f.experiences.manageDomainExperience(request, f.sign(request)));
        assertEquals(0, f.count("memory_domain_experience_event"));

        f.caller.setExperienceGovernancePublisher(true);
        f.auth.deniedPermission = GOVERNANCE_PERMISSION;
        code(MemoryErrorCode.FORBIDDEN, () -> f.experiences.manageDomainExperience(request, f.sign(request)));
        assertEquals(0, f.count("memory_domain_experience_event"));

        f.auth.deniedPermission = null;
        Instant otherAgentValidUntil = Instant.now().plusSeconds(3600);
        var otherAgent = f.request(MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_MANAGE,
                MemoryArchiveServiceTest.CALLER, "publish-other-agent", e -> new MemoryDomainExperienceManagementRequest(e,
                        CONTEXT, "dge_guidance-2", "listing-agent", 0, MemoryDomainExperienceManagementRequest.Action.PUBLISH,
                        "gov_approval-other-agent", "policy-v1", "review_other-agent", "domain-learning", "copy-draft",
                        "Verified facts", "When conditions match", "Keep claims specific", "Approved summary",
                        otherAgentValidUntil, true, true, null));
        code(MemoryErrorCode.FORBIDDEN, () -> f.experiences.manageDomainExperience(otherAgent, f.sign(otherAgent)));
        assertEquals(0, f.count("memory_domain_experience_event"));
    }

    @Test
    void rejectsPrivateLocatorsCredentialsAndUnattestedOrUnretainedCandidates() throws Exception {
        var f = new MemoryArchiveServiceTest.Fixture();
        Instant privateLocatorValidUntil = Instant.now().plusSeconds(3600);
        var privateLocator = f.request(MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_MANAGE,
                MemoryArchiveServiceTest.CALLER, "publish-private-reference", e -> new MemoryDomainExperienceManagementRequest(e,
                        CONTEXT, "dge_guidance-2", "copy-agent", 0, MemoryDomainExperienceManagementRequest.Action.PUBLISH,
                        "gov_approval-2", "policy-v1", "review_source-2", "domain-learning", "copy-draft",
                        "Verified facts", "When source_event_id=outcome-123 is present", "Keep claims specific",
                        "Approved summary", privateLocatorValidUntil, true, true, null));
        code(MemoryErrorCode.INVALID_EXTRACTION, () -> f.experiences.manageDomainExperience(privateLocator, f.sign(privateLocator)));
        assertEquals(0, f.count("memory_domain_experience"));

        assertThrows(MemoryException.class, () -> new MemoryDomainExperienceManagementRequest(
                privateLocator.envelope(), CONTEXT, "dge_guidance-2", "copy-agent", 0,
                MemoryDomainExperienceManagementRequest.Action.PUBLISH, "gov_approval-2", "policy-v1",
                "outcome-123", "domain-learning", "copy-draft", "Verified facts", "When conditions match",
                "Keep claims specific", "Approved summary", Instant.now().plusSeconds(3600), true, true, null));
        assertThrows(MemoryException.class, () -> new MemoryDomainExperienceManagementRequest(
                privateLocator.envelope(), CONTEXT, "dge_guidance-2", "copy-agent", 0,
                MemoryDomainExperienceManagementRequest.Action.PUBLISH, "gov_approval-2", "policy-v1",
                "review_source-2", "domain-learning", "copy-draft", "Verified facts", "When conditions match",
                "Keep claims specific", "Approved summary", Instant.now().plusSeconds(3600), false, true, null));
        assertThrows(MemoryException.class, () -> new MemoryDomainExperienceManagementRequest(
                privateLocator.envelope(), CONTEXT, "dge_guidance-2", "copy-agent", 0,
                MemoryDomainExperienceManagementRequest.Action.PUBLISH, "gov_approval-2", "policy-v1",
                "review_source-2", "domain-learning", "copy-draft", "Verified facts", "When conditions match",
                "Keep claims specific", "Approved summary", Instant.now().plusSeconds(3600), true, false, null));

        Instant credentialValidUntil = Instant.now().plusSeconds(3600);
        var credential = f.request(MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_MANAGE, e -> new MemoryDomainExperienceManagementRequest(e,
                CONTEXT, "dge_guidance-1", "copy-agent", 0, MemoryDomainExperienceManagementRequest.Action.PUBLISH,
                "gov_approval-credential", "policy-v1", "review_source-credential", "domain-learning", "copy-draft",
                "Verified facts", "When conditions match", "password=secret", "Approved summary",
                credentialValidUntil, true, true, null));
        code(MemoryErrorCode.INVALID_EXTRACTION, () -> f.experiences.manageDomainExperience(credential, f.sign(credential)));
        assertEquals(0, f.count("memory_domain_experience"));
    }

    @Test
    void approvedIndependentAggregateSurvivesPrivateSourceDeletionAndWithdrawalStopsNewReads() throws Exception {
        var f = new MemoryArchiveServiceTest.Fixture();
        var privateOutcome = outcome(f, "outcome-private-delete", "experience-private-delete");
        stage("submit private outcome", () -> f.experiences.submitOutcome(privateOutcome, f.signService(privateOutcome)));
        new MemoryExperienceProcessingWorker(f.store, f.experiences, f.experienceProperties, f.transactions).runOnce();
        var publish = publish(f, "publish-surviving-aggregate", 0, "经治理审核的非个人化结论");
        stage("publish independent aggregate", () -> f.experiences.manageDomainExperience(publish, f.sign(publish)));

        var remove = f.request(MemoryV2Contract.OPERATION_WITHDRAW,
                e -> new MemoryWithdrawalRequest(e, CONTEXT, "outcome-private-delete", true));
        assertEquals(MemoryState.DELETED, stage("delete private source", () -> f.service.withdrawSource(remove, f.sign(remove))).state());
        assertEquals("DELETED", f.jdbc.queryForObject("SELECT state FROM memory_experience", String.class));
        var privateSearch = f.request(MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES, e ->
                new MemoryExperienceQueryRequest(e, CONTEXT, null, null, 20, "private lesson", null, 1024));
        assertTrue(f.experiences.search(privateSearch, f.sign(privateSearch)).matches().isEmpty());

        var query = query(f, "read-surviving-aggregate", CONTEXT, null);
        var stillApproved = f.experiences.searchDomainExperiences(query, f.sign(query));
        assertEquals("经治理审核的非个人化结论", stillApproved.experiences().get(0).conclusion());
        assertFalse(f.mapper.writeValueAsString(stillApproved).contains("outcome-private-delete"));
        var approvedCopy = stillApproved.experiences().get(0);
        assertEquals(MemoryDomainExperienceUseCheckResponse.State.CURRENT,
                checkUse(f, "check-before-withdrawal", CONTEXT, approvedCopy).state());

        var withdraw = withdrawal(f, "withdraw-aggregate", 1, "governance approval expired");
        var withdrawn = f.experiences.manageDomainExperience(withdraw, f.sign(withdraw));
        assertEquals(MemoryDomainExperienceManagementResponse.State.WITHDRAWN, withdrawn.state());
        assertEquals(2, withdrawn.revision());
        var afterWithdrawal = query(f, "read-after-withdrawal", CONTEXT, null);
        assertEquals(0, f.experiences.searchDomainExperiences(afterWithdrawal, f.sign(afterWithdrawal)).experiences().size());
        assertEquals(MemoryDomainExperienceUseCheckResponse.State.WITHDRAWN,
                checkUse(f, "check-after-withdrawal", CONTEXT, approvedCopy).state());
        assertNull(f.jdbc.queryForObject("SELECT payload_json FROM memory_domain_experience WHERE aggregate_id='dge_guidance-1'", String.class));
    }

    private static MemoryDomainExperienceManagementRequest publish(MemoryArchiveServiceTest.Fixture f, String requestId,
                                                                    long expectedRevision, String conclusion) {
        Instant validUntil = Instant.now().plusSeconds(3600);
        return f.request(MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_MANAGE, MemoryArchiveServiceTest.CALLER, requestId,
                envelope -> new MemoryDomainExperienceManagementRequest(envelope, CONTEXT, "dge_guidance-1", "copy-agent",
                        expectedRevision, MemoryDomainExperienceManagementRequest.Action.PUBLISH, "gov_" + requestId,
                        "policy-v1", "review_" + requestId, "domain-learning", "copy-draft", "房源文案总结",
                        "仅在业务事实已经核验时使用", conclusion, "经受权治理审查的独立归纳",
                        validUntil, true, true, null));
    }

    private static MemoryDomainExperienceManagementRequest withdrawal(MemoryArchiveServiceTest.Fixture f, String requestId,
                                                                       long expectedRevision, String reason) {
        return f.request(MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_MANAGE, MemoryArchiveServiceTest.CALLER, requestId,
                envelope -> new MemoryDomainExperienceManagementRequest(envelope, CONTEXT, "dge_guidance-1", "copy-agent",
                        expectedRevision, MemoryDomainExperienceManagementRequest.Action.WITHDRAW, null, null, null, null,
                        null, null, null, null, null, null, false, false, reason));
    }

    private static MemoryDomainExperienceQueryRequest query(MemoryArchiveServiceTest.Fixture f, String requestId,
                                                              MemoryContextKey context, String cursor) {
        return f.request(MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_SEARCH, MemoryArchiveServiceTest.CALLER, requestId,
                envelope -> new MemoryDomainExperienceQueryRequest(envelope, context, "copy-draft", cursor, 10));
    }

    private static MemoryDomainExperienceUseCheckResponse checkUse(MemoryArchiveServiceTest.Fixture f, String requestId,
                                                                    MemoryContextKey context, MemoryDomainExperience aggregate) {
        var request = f.request(MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_CHECK, MemoryArchiveServiceTest.CALLER, requestId,
                envelope -> new MemoryDomainExperienceUseCheckRequest(envelope, context, aggregate.aggregateId(),
                        aggregate.revision(), aggregate.contentHash()));
        return f.experiences.checkDomainExperienceUse(request,
                Long.parseLong(context.ownerId()) == 42 ? f.sign(request) : signAs(f, request, Long.parseLong(context.ownerId())));
    }

    private static MemoryExperienceOutcomeRequest outcome(MemoryArchiveServiceTest.Fixture f, String evidenceId, String experienceId) {
        Instant occurredAt = Instant.now();
        var sourceRef = new MemorySourceRef(MemorySourceRef.Kind.OUTCOME, CONTEXT, "domain-result-" + evidenceId, evidenceId,
                null, new MemoryRevision(1, "a".repeat(64), "domain-outcome.v1"));
        return f.request(MemoryV2Contract.OPERATION_EXPERIENCE_OUTCOME, envelope -> new MemoryExperienceOutcomeRequest(envelope,
                CONTEXT, evidenceId, experienceId, 0, new MemoryAssociation("matter-1", null, null, null),
                "buyer request", "verified result", "private lesson", MemoryExperience.Outcome.SUCCEEDED,
                occurredAt, new MemorySourceDependency(sourceRef, null)));
    }

    private static MemoryRequestProof signAs(MemoryArchiveServiceTest.Fixture f, MemoryV2Request request, long owner) {
        long now = Instant.now().getEpochSecond();
        return MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(owner), "fixture-key", now, now + 60,
                UUID.randomUUID().toString().replace("-", ""), f.secret.getBytes(StandardCharsets.UTF_8));
    }

    private static <T> T stage(String label, java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch (MemoryException exception) { throw new AssertionError(label + ": " + exception.getMessage(), exception); }
    }

    private static void code(MemoryErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(MemoryException.class, action).code());
    }
}
