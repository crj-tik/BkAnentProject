package com.bkanent.common.memory;

import java.io.Serializable;

/** Current matter-effective value plus independently interpretable selection weights. */
public record MemoryPreferenceMatch(String dimensionId, PreferenceCurrentState effectiveState,
        MemorySelectionScore score, MemoryPreferenceSelectionRef selection) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryPreferenceMatch {
        dimensionId = MemoryContract.id(dimensionId, MemoryException.Field.PREFERENCE);
        MemoryContract.required(effectiveState, MemoryException.Field.PREFERENCE);
        MemoryContract.require(effectiveState.status() == PreferenceCurrentState.Status.EFFECTIVE, MemoryException.Field.PREFERENCE);
        MemoryContract.required(score, MemoryException.Field.SCORE);
        MemoryContract.required(selection, MemoryException.Field.PREFERENCE);
    }
}
