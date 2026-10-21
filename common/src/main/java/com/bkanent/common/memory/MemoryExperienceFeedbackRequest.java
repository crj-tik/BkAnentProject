package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/** Owner-authenticated feedback about one exact current private experience revision. */
public record MemoryExperienceFeedbackRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String evidenceId, String experienceId, long expectedRevision, MemoryAssociation association,
        MemoryExperience.Feedback feedback, Instant occurredAt, MemorySourceDependency source)
        implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryExperienceFeedbackRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_EXPERIENCE_FEEDBACK);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        evidenceId = MemoryContract.id(evidenceId, MemoryException.Field.EVIDENCE);
        experienceId = MemoryContract.id(experienceId, MemoryException.Field.SOURCE);
        MemoryContract.require(expectedRevision > 0, MemoryException.Field.VERSION);
        MemoryContract.required(association, MemoryException.Field.ASSOCIATION);
        MemoryContract.required(feedback, MemoryException.Field.EVIDENCE);
        MemoryContract.require(feedback != MemoryExperience.Feedback.NONE, MemoryException.Field.EVIDENCE);
        MemoryContract.required(occurredAt, MemoryException.Field.EVIDENCE);
        MemoryContract.required(source, MemoryException.Field.SOURCE);
        MemorySourceRef ref = source.source();
        MemoryContract.require(source.grant() == null && ref.kind() == MemorySourceRef.Kind.MESSAGE
                && contextKey.equals(ref.contextKey()), MemoryException.Field.SOURCE);
    }
}
