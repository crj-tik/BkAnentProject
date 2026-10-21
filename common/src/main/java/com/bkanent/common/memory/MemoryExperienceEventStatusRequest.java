package com.bkanent.common.memory;

import java.io.Serializable;

/** Reads processing metadata for one accepted evidence event; no evidence body is returned. */
public record MemoryExperienceEventStatusRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String evidenceId) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;
    public MemoryExperienceEventStatusRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_EXPERIENCE_STATUS);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        evidenceId = MemoryContract.id(evidenceId, MemoryException.Field.EVIDENCE);
    }
}
