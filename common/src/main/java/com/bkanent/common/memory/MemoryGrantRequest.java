package com.bkanent.common.memory;

import java.io.Serializable;

/** The requesting context is authenticated independently from the referenced source context. */
public record MemoryGrantRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                 MemoryExportRef sharedRef, MemoryScope scope, String matterId,
                                 String purpose) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;

    public MemoryGrantRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_GET_GRANT);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.required(sharedRef, MemoryException.Field.EXPORT);
        MemoryContract.require(scope != null && scope.kind() != MemoryScope.Kind.DOMAIN_PRIVATE, MemoryException.Field.SCOPE);
        matterId = MemoryContract.optionalId(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(scope.kind() != MemoryScope.Kind.TASK_SHARED || matterId != null, MemoryException.Field.GRANT);
        purpose = MemoryContract.id(purpose, MemoryException.Field.GRANT);
    }
}
