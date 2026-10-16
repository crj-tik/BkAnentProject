package com.bkanent.common.memory;

import java.io.Serializable;

/** Logical versioned locator only: no URL, collection, credential or assertion of current readability. */
public record MemorySourceRef(Kind kind, MemoryContextKey contextKey, String sourceId,
                              String sourceEventId, Long sourceSequence, MemoryRevision version)
        implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Kind { MESSAGE, DOMAIN_RECORD, GENERATED_ARTIFACT, MANUAL_REVISION, SUMMARY, SHARED_EXPORT, FEEDBACK, OUTCOME }

    public MemorySourceRef {
        MemoryContract.required(kind, MemoryException.Field.SOURCE);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        sourceId = MemoryContract.id(sourceId, MemoryException.Field.SOURCE);
        sourceEventId = MemoryContract.optionalId(sourceEventId, MemoryException.Field.SOURCE);
        MemoryContract.require(sourceSequence == null || sourceSequence > 0, MemoryException.Field.SOURCE);
        MemoryContract.require(kind != Kind.MESSAGE || sourceSequence != null, MemoryException.Field.SOURCE);
        MemoryContract.required(version, MemoryException.Field.VERSION);
    }
}
