package com.bkanent.common.memory;

import java.io.Serializable;

/** Searches preference blocks by trusted context; optional verified subject and dimension narrow the query. */
public record MemoryPreferenceQueryRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                            String verifiedSubjectRef, String dimensionId,
                                            String cursor, int limit) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryPreferenceQueryRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_SEARCH_PREFERENCES);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        verifiedSubjectRef = MemoryContract.optionalId(verifiedSubjectRef, MemoryException.Field.SCOPE);
        dimensionId = MemoryContract.optionalId(dimensionId, MemoryException.Field.PREFERENCE);
        cursor = MemoryContract.optionalId(cursor, MemoryException.Field.CURSOR);
        limit = MemoryV2Contract.requireLimit(limit);
    }
}
