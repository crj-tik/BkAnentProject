package com.bkanent.common.memory;

import java.io.Serializable;

/** Export durability and index readiness are separate; only COMMITTED returns a readable locator. */
public record MemorySharedExportStatusResponse(String sourceExportId, MemoryRevision version,
        MemorySharedExport.State state, MemoryExportRef committedRef, MemoryIndexState indexState) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemorySharedExportStatusResponse {
        sourceExportId = MemoryContract.id(sourceExportId, MemoryException.Field.EXPORT);
        MemoryContract.required(version, MemoryException.Field.VERSION);
        MemoryContract.required(state, MemoryException.Field.STATE);
        MemoryContract.required(indexState, MemoryException.Field.STATE);
        MemoryContract.require((state == MemorySharedExport.State.COMMITTED) == (committedRef != null), MemoryException.Field.EXPORT);
        MemoryContract.require(committedRef == null || committedRef.exportId().equals(sourceExportId)
                && committedRef.version().equals(version), MemoryException.Field.EXPORT);
        MemoryContract.require(indexState != MemoryIndexState.INDEX_READY || state == MemorySharedExport.State.COMMITTED,
                MemoryException.Field.STATE);
        MemoryContract.require(indexState != MemoryIndexState.INDEX_REMOVED
                || state == MemorySharedExport.State.WITHDRAWN || state == MemorySharedExport.State.DELETION_PENDING
                || state == MemorySharedExport.State.DELETED, MemoryException.Field.STATE);
    }
}
