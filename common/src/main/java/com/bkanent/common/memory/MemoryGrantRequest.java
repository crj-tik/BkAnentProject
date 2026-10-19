package com.bkanent.common.memory;

import java.io.Serializable;

/** Fetches a shared export's grant by its stable reference. */
public record MemoryGrantRequest(MemoryRequestEnvelope envelope, MemoryExportRef sharedRef) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryGrantRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_GET_GRANT);
        MemoryContract.required(sharedRef, MemoryException.Field.EXPORT);
    }

    @Override
    public MemoryContextKey contextKey() { return sharedRef.sourceContext(); }
}
