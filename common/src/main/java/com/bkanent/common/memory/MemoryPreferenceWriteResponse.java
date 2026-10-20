package com.bkanent.common.memory;

import java.io.Serializable;

public record MemoryPreferenceWriteResponse(String blockId, long revision, String transitionId, boolean applied) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryPreferenceWriteResponse {
        blockId = MemoryContract.id(blockId, MemoryException.Field.PREFERENCE);
        transitionId = MemoryContract.optionalId(transitionId, MemoryException.Field.PREFERENCE);
        MemoryContract.require(revision >= 0 && (!applied || revision > 0 && transitionId != null), MemoryException.Field.VERSION);
    }
}
