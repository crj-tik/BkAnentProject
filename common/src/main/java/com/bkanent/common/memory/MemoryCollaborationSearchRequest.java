package com.bkanent.common.memory;

import java.io.Serializable;

/** Bounded semantic search over exports currently authorized for this recipient context. */
public record MemoryCollaborationSearchRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String matterId, String purpose, String query, int limit) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;
    public static final int MAX_LIMIT = 50;

    public MemoryCollaborationSearchRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_SEARCH_COLLABORATION);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        matterId = MemoryContract.optionalId(matterId, MemoryException.Field.ASSOCIATION);
        purpose = MemoryContract.id(purpose, MemoryException.Field.GRANT);
        query = MemoryContract.text(query, MemoryException.Field.TEXT);
        MemoryContract.require(query.length() <= 4000, MemoryException.Field.TEXT);
        MemoryContract.require(limit > 0 && limit <= MAX_LIMIT, MemoryException.Field.PAGE);
    }

    @Override public String toString() { return "MemoryCollaborationSearchRequest[REDACTED]"; }
}
