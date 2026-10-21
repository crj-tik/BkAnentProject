package com.bkanent.common.memory;

import java.io.Serializable;

/** Reads lifecycle metadata for one export revision as its source owner. */
public record MemorySharedExportStatusRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String sourceExportId, long contentRevision) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;
    public MemorySharedExportStatusRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_SHARED_EXPORT_STATUS);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        sourceExportId = MemoryContract.id(sourceExportId, MemoryException.Field.EXPORT);
        MemoryContract.require(contentRevision > 0, MemoryException.Field.VERSION);
    }
}
