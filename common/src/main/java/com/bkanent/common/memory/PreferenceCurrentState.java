package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/** Current value is separate from history; selected exceptions/conflicts retain transition identities. */
public record PreferenceCurrentState(Status status, MemoryJsonValue value, List<String> transitionIds,
                                     List<String> exceptionTransitionIds, List<String> conflictTransitionIds,
                                     double confidence, double activation, Instant lastObservedAt,
                                     List<MemorySourceDependency> evidence, PreferenceTransition.Polarity polarity) implements Serializable {
    private static final long serialVersionUID = 1L;
    public PreferenceCurrentState(Status status, MemoryJsonValue value, List<String> transitionIds,
                                 List<String> exceptionTransitionIds, List<String> conflictTransitionIds,
                                 double confidence, double activation, Instant lastObservedAt, List<MemorySourceDependency> evidence) {
        this(status, value, transitionIds, exceptionTransitionIds, conflictTransitionIds, confidence, activation, lastObservedAt,
                evidence, status == Status.EFFECTIVE ? PreferenceTransition.Polarity.AFFIRMATIVE : null);
    }
    public enum Status { EFFECTIVE, UNRESOLVED, NO_EFFECTIVE_VALUE }
    public PreferenceCurrentState {
        MemoryContract.required(status, MemoryException.Field.PREFERENCE);
        MemoryContract.require(status == Status.EFFECTIVE ? value != null : value == null, MemoryException.Field.PREFERENCE);
        if (status == Status.EFFECTIVE && polarity == null) polarity = PreferenceTransition.Polarity.AFFIRMATIVE; // old v2 JSON
        MemoryContract.require(status == Status.EFFECTIVE || polarity == null, MemoryException.Field.PREFERENCE);
        transitionIds = ids(transitionIds);
        exceptionTransitionIds = ids(exceptionTransitionIds);
        conflictTransitionIds = ids(conflictTransitionIds);
        MemoryContract.require(status != Status.EFFECTIVE || !transitionIds.isEmpty(), MemoryException.Field.PREFERENCE);
        MemoryContract.require(status != Status.UNRESOLVED || !conflictTransitionIds.isEmpty(), MemoryException.Field.PREFERENCE);
        MemoryContract.unit(confidence);
        MemoryContract.unit(activation);
        evidence = MemoryContract.list(evidence, MemoryException.Field.EVIDENCE);
        MemoryContract.require(status == Status.NO_EFFECTIVE_VALUE || !evidence.isEmpty() && lastObservedAt != null, MemoryException.Field.EVIDENCE);
    }
    private static List<String> ids(List<String> values) {
        List<String> copy = MemoryContract.unique(values, MemoryException.Field.PREFERENCE);
        copy.forEach(id -> MemoryContract.id(id, MemoryException.Field.PREFERENCE));
        return copy;
    }
}
