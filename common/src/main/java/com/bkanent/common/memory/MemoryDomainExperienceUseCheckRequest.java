package com.bkanent.common.memory;

import java.io.Serializable;

/** Binds one use check to the exact aggregate revision assembled by a caller. */
public record MemoryDomainExperienceUseCheckRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String aggregateId, long expectedRevision, String expectedContentHash) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryDomainExperienceUseCheckRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_DOMAIN_EXPERIENCE_CHECK);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.require(aggregateId != null && aggregateId.matches("dge_[A-Za-z0-9._:%-]{1,120}"),
                MemoryException.Field.IDENTITY);
        MemoryContract.require(expectedRevision > 0, MemoryException.Field.VERSION);
        MemoryContract.require(expectedContentHash != null && expectedContentHash.matches("[0-9a-f]{64}"),
                MemoryException.Field.HASH);
    }
}
