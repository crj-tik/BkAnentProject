package com.bkanent.common.memory;

import java.io.Serializable;

public record MemoryWriterRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey, Action action,
                                   String runId, long fence) implements Serializable, MemoryV2Request {
    public enum Action { ACQUIRE, RENEW, RELEASE }
    public MemoryWriterRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_WRITER);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.required(action, MemoryException.Field.IDENTITY);
        runId = MemoryContract.id(runId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(fence >= 0 && (action == Action.ACQUIRE || fence > 0), MemoryException.Field.VERSION);
    }
}
