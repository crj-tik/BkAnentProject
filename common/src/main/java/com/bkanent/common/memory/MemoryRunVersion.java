package com.bkanent.common.memory;

import java.io.Serializable;

/** Execution checkpoint revision does not advance or replace a conversation/source revision. */
public record MemoryRunVersion(String runId, long checkpointRevision) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryRunVersion {
        runId = MemoryContract.id(runId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(checkpointRevision >= 0, MemoryException.Field.VERSION);
    }
}
