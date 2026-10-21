package com.bkanent.common.memory;

import java.io.Serializable;

public record MemoryMatterWriteRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        MemoryMatterIntent intent, MemorySourceDependency evidence, long expectedConversationRevision,
        MemoryWriteAuthority writer) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;
    public MemoryMatterWriteRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_MATTER_WRITE);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.required(intent, MemoryException.Field.STATE);
        MemoryContract.required(evidence, MemoryException.Field.EVIDENCE);
        MemoryContract.require(evidence.grant() == null && evidence.source().kind() == MemorySourceRef.Kind.MESSAGE
                && contextKey.equals(evidence.source().contextKey()), MemoryException.Field.SOURCE);
        MemoryContract.require(expectedConversationRevision >= 0, MemoryException.Field.VERSION);
        MemoryContract.required(writer, MemoryException.Field.IDENTITY);
    }
}
