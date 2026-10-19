package com.bkanent.common.memory;

import java.io.Serializable;

/** Cold-start working set read: one context's current authorized material. */
public record MemoryWorkingSetRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey)
        implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryWorkingSetRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_WORKING_SET);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
    }
}
