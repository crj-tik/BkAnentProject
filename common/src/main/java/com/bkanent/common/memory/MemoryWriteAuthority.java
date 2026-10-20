package com.bkanent.common.memory;

import java.io.Serializable;

/** Fencing identity, never a bearer credential. A current signed user request is still required. */
public record MemoryWriteAuthority(String runId, long fence) implements Serializable {
    public MemoryWriteAuthority {
        runId = MemoryContract.id(runId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(fence > 0, MemoryException.Field.VERSION);
    }
}
