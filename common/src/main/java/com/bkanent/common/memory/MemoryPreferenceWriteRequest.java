package com.bkanent.common.memory;

import java.io.Serializable;

/** Authenticated assessed-candidate submission; this is not an asynchronous processing credential. */
public record MemoryPreferenceWriteRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        long expectedRevision, MemoryPreferenceAssessment candidate) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;
    public MemoryPreferenceWriteRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_PREFERENCE_WRITE);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.require(expectedRevision >= 0, MemoryException.Field.VERSION);
        MemoryContract.required(candidate, MemoryException.Field.PREFERENCE);
        MemoryContract.require(contextKey.equals(candidate.source().source().contextKey()) && candidate.source().grant() == null,
                MemoryException.Field.SOURCE);
    }
}
