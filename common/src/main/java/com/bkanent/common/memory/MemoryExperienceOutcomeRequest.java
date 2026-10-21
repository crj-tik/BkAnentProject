package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/** A trusted domain publisher's immutable result event. SERVICE signatures can submit only this operation. */
public record MemoryExperienceOutcomeRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String evidenceId, String experienceId, long expectedRevision, MemoryAssociation association,
        String scenario, String applicability, String conclusion, MemoryExperience.Outcome outcome,
        Instant occurredAt, MemorySourceDependency source) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryExperienceOutcomeRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_EXPERIENCE_OUTCOME);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        evidenceId = MemoryContract.id(evidenceId, MemoryException.Field.EVIDENCE);
        experienceId = MemoryContract.id(experienceId, MemoryException.Field.SOURCE);
        MemoryContract.require(expectedRevision >= 0, MemoryException.Field.VERSION);
        MemoryContract.required(association, MemoryException.Field.ASSOCIATION);
        scenario = boundedText(scenario);
        applicability = boundedText(applicability);
        conclusion = boundedText(conclusion);
        MemoryContract.required(outcome, MemoryException.Field.EVIDENCE);
        MemoryContract.require(outcome == MemoryExperience.Outcome.SUCCEEDED
                || outcome == MemoryExperience.Outcome.FAILED || outcome == MemoryExperience.Outcome.PARTIAL,
                MemoryException.Field.EVIDENCE);
        MemoryContract.required(occurredAt, MemoryException.Field.EVIDENCE);
        MemoryContract.required(source, MemoryException.Field.SOURCE);
        MemorySourceRef ref = source.source();
        MemoryContract.require(source.grant() == null && contextKey.equals(ref.contextKey())
                && (ref.kind() == MemorySourceRef.Kind.OUTCOME || ref.kind() == MemorySourceRef.Kind.DOMAIN_RECORD)
                && evidenceId.equals(ref.sourceEventId()), MemoryException.Field.SOURCE);
    }

    private static String boundedText(String value) {
        MemoryContract.text(value, MemoryException.Field.TEXT);
        MemoryContract.require(value.length() <= 4000, MemoryException.Field.TEXT);
        return value;
    }
}
