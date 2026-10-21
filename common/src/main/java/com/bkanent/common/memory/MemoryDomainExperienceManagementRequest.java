package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/**
 * Governance-only publication or withdrawal. No private source/experience reference
 * is accepted; source review is represented only by a governance-issued opaque handle.
 */
public record MemoryDomainExperienceManagementRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String aggregateId, String targetAgentId, long expectedRevision, Action action,
        String governanceApprovalId, String policyVersion, String sourceReviewRef, String purpose,
        String capabilityTag, String scenario, String applicability, String conclusion, String evidenceSummary,
        Instant validUntil, boolean independentNonPersonal, boolean retentionAuthorized,
        String withdrawalReason) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public enum Action { PUBLISH, WITHDRAW }

    public MemoryDomainExperienceManagementRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_MANAGE);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.require(aggregateId != null && aggregateId.matches("dge_[A-Za-z0-9._:%-]{1,120}"),
                MemoryException.Field.IDENTITY);
        targetAgentId = MemoryContract.id(targetAgentId, MemoryException.Field.SCOPE);
        MemoryContract.require(expectedRevision >= 0, MemoryException.Field.VERSION);
        MemoryContract.required(action, MemoryException.Field.STATE);
        capabilityTag = MemoryContract.optionalId(capabilityTag, MemoryException.Field.SCOPE);
        if (action == Action.PUBLISH) {
            MemoryContract.require(governanceApprovalId != null && governanceApprovalId.matches("gov_[A-Za-z0-9._:%-]{1,120}"),
                    MemoryException.Field.IDENTITY);
            MemoryContract.require(sourceReviewRef != null && sourceReviewRef.matches("review_[A-Za-z0-9._:%-]{1,120}"),
                    MemoryException.Field.IDENTITY);
            policyVersion = MemoryContract.id(policyVersion, MemoryException.Field.VERSION);
            purpose = MemoryContract.id(purpose, MemoryException.Field.SCOPE);
            scenario = MemoryContract.text(scenario, MemoryException.Field.TEXT);
            applicability = MemoryContract.text(applicability, MemoryException.Field.TEXT);
            conclusion = MemoryContract.text(conclusion, MemoryException.Field.TEXT);
            evidenceSummary = MemoryContract.text(evidenceSummary, MemoryException.Field.TEXT);
            MemoryContract.required(validUntil, MemoryException.Field.EVIDENCE);
            MemoryContract.require(independentNonPersonal && retentionAuthorized, MemoryException.Field.STATE);
            MemoryContract.require(withdrawalReason == null, MemoryException.Field.STATE);
        } else {
            MemoryContract.require(expectedRevision > 0, MemoryException.Field.VERSION);
            MemoryContract.text(withdrawalReason, MemoryException.Field.TEXT);
            MemoryContract.require(withdrawalReason.length() <= 1000, MemoryException.Field.TEXT);
            MemoryContract.require(governanceApprovalId == null && policyVersion == null && sourceReviewRef == null
                    && purpose == null && capabilityTag == null && scenario == null && applicability == null
                    && conclusion == null && evidenceSummary == null && validUntil == null
                    && !independentNonPersonal && !retentionAuthorized, MemoryException.Field.STATE);
        }
    }
}
