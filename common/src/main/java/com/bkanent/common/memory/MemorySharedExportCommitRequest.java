package com.bkanent.common.memory;

import java.io.Serializable;

/** Confirms one staged revision after source and grant truth are rechecked. */
public record MemorySharedExportCommitRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String sourceExportId, long contentRevision, String contentHash) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;
    public MemorySharedExportCommitRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_SHARED_EXPORT_COMMIT);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        sourceExportId = MemoryContract.id(sourceExportId, MemoryException.Field.EXPORT);
        MemoryContract.require(contentRevision > 0, MemoryException.Field.VERSION);
        MemoryContract.require(MemoryV2Contract.validHash(contentHash), MemoryException.Field.HASH);
    }
}
