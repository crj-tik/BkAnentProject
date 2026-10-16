package com.bkanent.common.memory;

import java.io.Serializable;

/** Dependency locator; possession does not establish a valid current grant. */
public record MemoryGrantRef(String grantId, long revision) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryGrantRef {
        grantId = MemoryContract.id(grantId, MemoryException.Field.GRANT);
        MemoryContract.require(revision > 0, MemoryException.Field.VERSION);
    }
}
