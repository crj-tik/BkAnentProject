package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/** Owner/domain/agent private soft experience only. Public governed generalizations need an independent contract. */
public record MemoryExperience(String experienceId, MemoryContextKey contextKey, MemoryAssociation association,
                               MemoryRevision version, String scenario, String applicability, String conclusion,
                               MemoryState state, String changeReason, Long supersedesRevision,
                               double confidence, double activation, Instant lastObservedAt, Instant lastValidatedAt,
                               String policyVersion, List<Evidence> evidence) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum EvidenceKind { BUSINESS_OUTCOME, USER_FEEDBACK, MODEL_SELF_ASSESSMENT, ACCEPTANCE }
    public enum Outcome { SUCCEEDED, FAILED, PARTIAL, UNKNOWN, ACCEPTED }
    public enum Feedback { POSITIVE, NEGATIVE, NEUTRAL, NONE }
    public record Evidence(String evidenceId, EvidenceKind kind, Outcome outcome, Feedback feedback,
                           boolean verified, Instant occurredAt, Instant observedAt,
                           List<MemorySourceDependency> sources) implements Serializable {
        private static final long serialVersionUID = 1L;
        public Evidence {
            evidenceId = MemoryContract.id(evidenceId, MemoryException.Field.EVIDENCE);
            MemoryContract.required(kind, MemoryException.Field.EVIDENCE);
            MemoryContract.required(outcome, MemoryException.Field.EVIDENCE);
            MemoryContract.required(feedback, MemoryException.Field.EVIDENCE);
            MemoryContract.required(occurredAt, MemoryException.Field.EVIDENCE);
            MemoryContract.required(observedAt, MemoryException.Field.EVIDENCE);
            sources = MemoryContract.nonempty(sources, MemoryException.Field.EVIDENCE);
            MemoryContract.require(!verified || (kind == EvidenceKind.BUSINESS_OUTCOME
                    && outcome != Outcome.UNKNOWN && outcome != Outcome.ACCEPTED)
                    || (kind == EvidenceKind.USER_FEEDBACK && feedback != Feedback.NONE),
                    MemoryException.Field.EVIDENCE);
            MemoryContract.require(kind != EvidenceKind.ACCEPTANCE || outcome == Outcome.ACCEPTED, MemoryException.Field.EVIDENCE);
        }
    }
    public MemoryExperience {
        experienceId = MemoryContract.id(experienceId, MemoryException.Field.SOURCE);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.required(association, MemoryException.Field.ASSOCIATION);
        MemoryContract.required(version, MemoryException.Field.VERSION);
        scenario = MemoryContract.text(scenario, MemoryException.Field.TEXT);
        applicability = MemoryContract.text(applicability, MemoryException.Field.TEXT);
        conclusion = MemoryContract.text(conclusion, MemoryException.Field.TEXT);
        MemoryContract.required(state, MemoryException.Field.STATE);
        changeReason = MemoryContract.text(changeReason, MemoryException.Field.TEXT);
        MemoryContract.require(supersedesRevision == null || supersedesRevision > 0
                && supersedesRevision < version.revision(), MemoryException.Field.VERSION);
        MemoryContract.unit(confidence);
        MemoryContract.unit(activation);
        MemoryContract.required(lastObservedAt, MemoryException.Field.EVIDENCE);
        policyVersion = MemoryContract.id(policyVersion, MemoryException.Field.VERSION);
        evidence = MemoryContract.nonempty(evidence, MemoryException.Field.EVIDENCE);
        MemoryContract.require(evidence.stream().map(Evidence::evidenceId).distinct().count() == evidence.size(), MemoryException.Field.EVIDENCE);
        MemoryContract.require(lastValidatedAt == null || !lastValidatedAt.isAfter(lastObservedAt)
                && evidence.stream().anyMatch(Evidence::verified), MemoryException.Field.EVIDENCE);
    }
}
