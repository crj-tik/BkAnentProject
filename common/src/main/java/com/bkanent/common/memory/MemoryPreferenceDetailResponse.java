package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/** Block is the immutable revision snapshot. effectiveState is its authorized view at the requested asOf/matter. */
public record MemoryPreferenceDetailResponse(PreferenceBlock block, PreferenceHistoryPage history,
        PreferenceCurrentState effectiveState, Instant asOf) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryPreferenceDetailResponse {
        MemoryContract.required(block, MemoryException.Field.PREFERENCE);
        MemoryContract.required(history, MemoryException.Field.PAGE);
        MemoryContract.required(effectiveState, MemoryException.Field.PREFERENCE);
        MemoryContract.required(asOf, MemoryException.Field.PREFERENCE);
        MemoryContract.require(history.cursor().blockId().equals(block.blockId()) && history.cursor().revision() == block.version().revision(), MemoryException.Field.VERSION);
    }
}
