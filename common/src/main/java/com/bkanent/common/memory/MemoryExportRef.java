package com.bkanent.common.memory;

import java.io.Serializable;

/** Committed versioned locator only; still requires current owner/scope/purpose authorization. */
public record MemoryExportRef(String exportId, MemoryContextKey sourceContext, MemoryRevision version)
        implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryExportRef {
        exportId = MemoryContract.id(exportId, MemoryException.Field.EXPORT);
        MemoryContract.required(sourceContext, MemoryException.Field.IDENTITY);
        MemoryContract.required(version, MemoryException.Field.VERSION);
    }
}
