package com.bkanent.common.memory;

import java.io.Serializable;

/** Requests a bounded private preference projection for a server-owned active matter. */
public record MemoryPreferenceAssemblyRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String matterId, int tokenBudget) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryPreferenceAssemblyRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_ASSEMBLE_PREFERENCES);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        matterId = MemoryContract.id(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(tokenBudget >= 1 && tokenBudget <= 4096, MemoryException.Field.PAGE);
    }
}
