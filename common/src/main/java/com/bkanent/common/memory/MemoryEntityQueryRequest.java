package com.bkanent.common.memory;

import java.io.Serializable;

/** Paged entity-anchor query inside one context. */
public record MemoryEntityQueryRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                        String cursor, int limit) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryEntityQueryRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_QUERY_ENTITIES);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        cursor = MemoryContract.optionalId(cursor, MemoryException.Field.CURSOR);
        limit = MemoryV2Contract.requireLimit(limit);
    }
}
