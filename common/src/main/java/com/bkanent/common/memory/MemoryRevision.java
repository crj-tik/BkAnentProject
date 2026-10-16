package com.bkanent.common.memory;

import java.io.Serializable;

/** Immutable source/content version, separate from run/checkpoint revision and persistence readiness. */
public record MemoryRevision(long revision, String contentHash, String schemaVersion) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryRevision {
        MemoryContract.require(revision > 0, MemoryException.Field.VERSION);
        MemoryContract.require(contentHash != null && contentHash.matches("[a-f0-9]{64}"), MemoryException.Field.HASH);
        schemaVersion = MemoryContract.id(schemaVersion, MemoryException.Field.VERSION);
    }
}
