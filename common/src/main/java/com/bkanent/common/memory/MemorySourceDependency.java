package com.bkanent.common.memory;

import java.io.Serializable;

/** A private source has no grant reference; shared sources retain the grant revision used. */
public record MemorySourceDependency(MemorySourceRef source, MemoryGrantRef grant) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemorySourceDependency { MemoryContract.required(source, MemoryException.Field.SOURCE); }
}
