package com.bkanent.common.memory;

import java.io.Serializable;

/** One logical dimension with a fixed revision history entry point, not a physical retrieval fragment. */
public record PreferenceBlock(String blockId, PreferenceBlockKey key, MemoryRevision version,
                              MemoryState state, PreferenceCurrentState currentState,
                              PreferenceHistoryCursor historyStart) implements Serializable {
    private static final long serialVersionUID = 1L;
    public PreferenceBlock {
        blockId = MemoryContract.id(blockId, MemoryException.Field.PREFERENCE);
        MemoryContract.required(key, MemoryException.Field.IDENTITY);
        MemoryContract.required(version, MemoryException.Field.VERSION);
        MemoryContract.required(state, MemoryException.Field.STATE);
        MemoryContract.required(currentState, MemoryException.Field.PREFERENCE);
        MemoryContract.require(historyStart != null && historyStart.blockId().equals(blockId)
                && historyStart.revision() == version.revision() && historyStart.afterOrdinal() == 0, MemoryException.Field.CURSOR);
    }
}
