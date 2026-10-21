package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Stages one bounded immutable source-domain export revision. It is unreadable until committed. */
public record MemorySharedExportSubmitRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String sourceExportId, long expectedRevision, String sourceInvocationId, MemoryAssociation association,
        MemoryGrantRef grant, String purpose, String brief, MemoryJsonValue payload,
        List<MemorySourceDependency> dependencies) implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;
    public static final String SCHEMA_VERSION = "memory.export.v2";
    public MemorySharedExportSubmitRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_SHARED_EXPORT_SUBMIT);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        sourceExportId = MemoryContract.id(sourceExportId, MemoryException.Field.EXPORT);
        MemoryContract.require(expectedRevision >= 0, MemoryException.Field.VERSION);
        sourceInvocationId = MemoryContract.id(sourceInvocationId, MemoryException.Field.SOURCE);
        MemoryContract.required(association, MemoryException.Field.ASSOCIATION);
        MemoryContract.required(grant, MemoryException.Field.GRANT);
        purpose = MemoryContract.id(purpose, MemoryException.Field.GRANT);
        brief = MemoryContract.optionalText(brief, MemoryException.Field.TEXT);
        MemoryContract.required(payload, MemoryException.Field.JSON);
        MemoryContract.require(payload.type() == MemoryJsonValue.Type.OBJECT, MemoryException.Field.JSON);
        dependencies = MemoryContract.nonempty(dependencies, MemoryException.Field.SOURCE);
    }
}
