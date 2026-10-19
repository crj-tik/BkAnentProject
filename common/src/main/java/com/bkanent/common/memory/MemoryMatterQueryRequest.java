package com.bkanent.common.memory;

import java.io.Serializable;

/** Paged matter query inside one context; cursor is opaque and versioned by the server. */
public record MemoryMatterQueryRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                        String cursor, int limit) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryMatterQueryRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_QUERY_MATTERS);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        cursor = MemoryContract.optionalId(cursor, MemoryException.Field.CURSOR);
        limit = MemoryV2Contract.requireLimit(limit);
    }
}
