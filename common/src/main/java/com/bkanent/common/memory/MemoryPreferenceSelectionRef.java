package com.bkanent.common.memory;

import java.io.Serializable;

/** Body-free pin for reauthorizing a selected preference before a later model output. */
public record MemoryPreferenceSelectionRef(String blockId, long revision, String contentHash,
        long authorizationRevision, String matterId, long matterRevision,
        String effectiveStateHash) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryPreferenceSelectionRef {
        blockId = MemoryContract.id(blockId, MemoryException.Field.PREFERENCE);
        MemoryContract.require(revision > 0 && authorizationRevision > 0 && matterRevision > 0, MemoryException.Field.VERSION);
        MemoryContract.require(contentHash != null && contentHash.matches("[a-f0-9]{64}"), MemoryException.Field.HASH);
        matterId = MemoryContract.id(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(effectiveStateHash != null && effectiveStateHash.matches("[a-f0-9]{64}"), MemoryException.Field.HASH);
    }
}
