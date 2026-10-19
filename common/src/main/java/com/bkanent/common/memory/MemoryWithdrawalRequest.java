package com.bkanent.common.memory;

import java.io.Serializable;

/** Private archive withdrawal. External projection cleanup is separately acknowledged. */
public record MemoryWithdrawalRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                       String sourceEventId, boolean delete) implements Serializable, MemoryV2Request {
    public MemoryWithdrawalRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_WITHDRAW);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        sourceEventId = MemoryContract.id(sourceEventId, MemoryException.Field.SOURCE);
    }
}
