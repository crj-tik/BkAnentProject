package com.bkanent.common.memory;

import java.io.Serializable;

/** Reads cleanup progress for one owner-authorized source event. */
public record MemoryPrivacyCleanupStatusRequest(MemoryRequestEnvelope envelope,
                                                MemoryContextKey contextKey,
                                                String sourceEventId) implements Serializable, MemoryV2Request {
    public MemoryPrivacyCleanupStatusRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_PRIVACY_STATUS);
        MemoryContract.required(contextKey, MemoryException.Field.ASSOCIATION);
        sourceEventId = MemoryContract.id(sourceEventId, MemoryException.Field.SOURCE);
    }
}
