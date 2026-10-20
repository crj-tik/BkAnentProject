package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/** Candidate semantics only. Owner, scope, event identity, ordering and observation time are server-owned. */
public record MemoryPreferenceAssessment(String dimensionId, PreferenceTransition.Assessment assessment,
        PreferenceTransition.Change change, PreferenceTransition.Polarity polarity, MemoryJsonValue value,
        String applicability, String matterId, Instant effectiveFrom, Instant effectiveUntil,
        List<String> correctsTransitionIds, double confidence, String extractionVersion,
        MemorySourceDependency source) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryPreferenceAssessment {
        dimensionId = MemoryContract.id(dimensionId, MemoryException.Field.PREFERENCE);
        MemoryContract.required(assessment, MemoryException.Field.PREFERENCE);
        MemoryContract.required(change, MemoryException.Field.PREFERENCE);
        MemoryContract.required(polarity, MemoryException.Field.PREFERENCE);
        MemoryContract.required(value, MemoryException.Field.PREFERENCE);
        applicability = MemoryContract.optionalText(applicability, MemoryException.Field.TEXT);
        matterId = MemoryContract.optionalId(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.interval(effectiveFrom, effectiveUntil, MemoryException.Field.PREFERENCE);
        correctsTransitionIds = MemoryContract.unique(correctsTransitionIds, MemoryException.Field.PREFERENCE);
        correctsTransitionIds.forEach(id -> MemoryContract.id(id, MemoryException.Field.PREFERENCE));
        MemoryContract.unit(confidence);
        extractionVersion = MemoryContract.id(extractionVersion, MemoryException.Field.VERSION);
        MemoryContract.required(source, MemoryException.Field.SOURCE);
    }
}
