package com.bkanent.common.memory;

import java.io.Serializable;

/** Reads published generalizations from the exact authenticated domain/agent scope. */
public record MemoryDomainExperienceQueryRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String capabilityTag, String cursor, int limit) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryDomainExperienceQueryRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_SEARCH);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        capabilityTag = MemoryContract.optionalId(capabilityTag, MemoryException.Field.SCOPE);
        cursor = MemoryContract.optionalId(cursor, MemoryException.Field.CURSOR);
        MemoryContract.require(cursor == null || cursor.matches("dge_[A-Za-z0-9._:%-]{1,120}"), MemoryException.Field.CURSOR);
        MemoryContract.require(limit > 0 && limit <= 50, MemoryException.Field.PAGE);
    }
}
