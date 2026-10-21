package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Body-free reuse check for an already loaded working set or checkpoint projection. */
public record MemoryBoundaryRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                     MemoryConversationVersion adoptedVersion,
                                     List<MemorySourceDependency> dependencies,
                                     List<MemoryPreferenceSelectionRef> preferenceSelections) implements Serializable, MemoryV2Request {
    public MemoryBoundaryRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
                                 MemoryConversationVersion adoptedVersion, List<MemorySourceDependency> dependencies) {
        this(envelope, contextKey, adoptedVersion, dependencies, List.of());
    }
    public MemoryBoundaryRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_BOUNDARY);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.required(adoptedVersion, MemoryException.Field.VERSION);
        dependencies = MemoryContract.list(dependencies, MemoryException.Field.SOURCE);
        preferenceSelections = MemoryContract.list(preferenceSelections, MemoryException.Field.PREFERENCE);
    }
}
