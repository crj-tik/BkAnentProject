package com.bkanent.common.memory;

import java.io.Serializable;

/** Searches decaying agent experience by trusted context; an optional capability tag narrows the query. */
public record MemoryExperienceQueryRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                            String capabilityTag, String cursor, int limit, String objective,
                                            String matterId, int tokenBudget) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryExperienceQueryRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_SEARCH_EXPERIENCES);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        capabilityTag = MemoryContract.optionalId(capabilityTag, MemoryException.Field.SCOPE);
        cursor = MemoryContract.optionalId(cursor, MemoryException.Field.CURSOR);
        limit = MemoryV2Contract.requireLimit(limit);
        objective = MemoryContract.optionalText(objective, MemoryException.Field.TEXT);
        MemoryContract.require(objective == null || objective.length() <= 4000, MemoryException.Field.TEXT);
        matterId = MemoryContract.optionalId(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(tokenBudget >= 1 && tokenBudget <= 4096, MemoryException.Field.PAGE);
    }

    /** Source-compatible default for callers that only need a bounded capability lookup. */
    public MemoryExperienceQueryRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
            String capabilityTag, String cursor, int limit) {
        this(envelope, contextKey, capabilityTag, cursor, limit, capabilityTag, null, 1024);
    }
}
