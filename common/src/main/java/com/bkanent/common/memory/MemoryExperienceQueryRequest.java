package com.bkanent.common.memory;

import java.io.Serializable;

/** Searches decaying agent experience by trusted context; an optional capability tag narrows the query. */
public record MemoryExperienceQueryRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                            String capabilityTag, String cursor, int limit) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryExperienceQueryRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        capabilityTag = MemoryContract.optionalId(capabilityTag, MemoryException.Field.SCOPE);
        cursor = MemoryContract.optionalId(cursor, MemoryException.Field.CURSOR);
        limit = MemoryV2Contract.requireLimit(limit);
    }
}
