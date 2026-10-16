package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/** Source sequence is scoped by each evidence context; historyOrdinal orders a fixed block view only. */
public record PreferenceTransition(String transitionId, long historyOrdinal, long introducedRevision,
                                   Assessment assessment, Change change, Polarity polarity,
                                   MemoryJsonValue value, String applicability, String matterId,
                                   Instant occurredAt, Instant observedAt, Instant effectiveFrom, Instant effectiveUntil,
                                   List<String> correctsTransitionIds, double confidence,
                                   String extractionVersion, List<MemorySourceDependency> evidence) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Assessment { PREFERENCE, TASK_CONSTRAINT, QUESTION, OTHER }
    public enum Change { OBSERVATION, CONFIRMATION, LONG_TERM_CORRECTION, TEMPORARY_EXCEPTION, UNRESOLVED_CONFLICT, WITHDRAWAL }
    public enum Polarity { AFFIRMATIVE, NEGATIVE }

    public PreferenceTransition {
        transitionId = MemoryContract.id(transitionId, MemoryException.Field.PREFERENCE);
        MemoryContract.require(historyOrdinal > 0 && introducedRevision > 0, MemoryException.Field.VERSION);
        MemoryContract.required(assessment, MemoryException.Field.PREFERENCE);
        MemoryContract.required(change, MemoryException.Field.PREFERENCE);
        MemoryContract.required(polarity, MemoryException.Field.PREFERENCE);
        MemoryContract.required(value, MemoryException.Field.PREFERENCE);
        applicability = MemoryContract.optionalText(applicability, MemoryException.Field.TEXT);
        matterId = MemoryContract.optionalId(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.required(occurredAt, MemoryException.Field.EVIDENCE);
        MemoryContract.required(observedAt, MemoryException.Field.EVIDENCE);
        MemoryContract.interval(effectiveFrom, effectiveUntil, MemoryException.Field.PREFERENCE);
        correctsTransitionIds = MemoryContract.unique(correctsTransitionIds, MemoryException.Field.PREFERENCE);
        correctsTransitionIds.forEach(id -> MemoryContract.id(id, MemoryException.Field.PREFERENCE));
        MemoryContract.require(!correctsTransitionIds.contains(transitionId), MemoryException.Field.PREFERENCE);
        MemoryContract.require(change != Change.LONG_TERM_CORRECTION || !correctsTransitionIds.isEmpty(), MemoryException.Field.PREFERENCE);
        MemoryContract.require(change != Change.TEMPORARY_EXCEPTION || matterId != null || effectiveUntil != null, MemoryException.Field.PREFERENCE);
        MemoryContract.unit(confidence);
        extractionVersion = MemoryContract.id(extractionVersion, MemoryException.Field.VERSION);
        evidence = MemoryContract.nonempty(evidence, MemoryException.Field.EVIDENCE);
    }
}
