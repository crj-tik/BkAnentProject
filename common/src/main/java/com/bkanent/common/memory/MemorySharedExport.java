package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Durable export only, not display/collaboration payload. PENDING has a stable source ID but no readable ref. */
public record MemorySharedExport(String sourceExportId, MemoryContextKey sourceContext,
                                 MemoryAssociation association, String sourceInvocationId, MemoryRevision version,
                                 MemoryGrantRef grant, String purpose, String brief, MemoryJsonValue payload,
                                 List<MemorySourceDependency> dependencies, State state,
                                 MemoryExportRef committedRef, MemoryIndexState indexState) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum State { PENDING, COMMITTED, FAILED, WITHDRAWN, DELETION_PENDING, DELETED }
    public MemorySharedExport {
        sourceExportId = MemoryContract.id(sourceExportId, MemoryException.Field.EXPORT);
        MemoryContract.required(sourceContext, MemoryException.Field.IDENTITY);
        MemoryContract.required(association, MemoryException.Field.ASSOCIATION);
        sourceInvocationId = MemoryContract.id(sourceInvocationId, MemoryException.Field.SOURCE);
        MemoryContract.required(version, MemoryException.Field.VERSION);
        MemoryContract.required(grant, MemoryException.Field.GRANT);
        purpose = MemoryContract.id(purpose, MemoryException.Field.GRANT);
        brief = MemoryContract.optionalText(brief, MemoryException.Field.TEXT);
        dependencies = MemoryContract.nonempty(dependencies, MemoryException.Field.SOURCE);
        MemoryContract.required(state, MemoryException.Field.STATE);
        MemoryContract.required(indexState, MemoryException.Field.STATE);
        MemoryContract.require(state != State.COMMITTED || committedRef != null && payload != null, MemoryException.Field.EXPORT);
        MemoryContract.require(state != State.PENDING && state != State.FAILED || committedRef == null, MemoryException.Field.EXPORT);
        MemoryContract.require(committedRef == null || committedRef.sourceContext().equals(sourceContext)
                && committedRef.version().equals(version), MemoryException.Field.EXPORT);
        MemoryContract.require(indexState != MemoryIndexState.INDEX_READY || state == State.COMMITTED, MemoryException.Field.STATE);
        MemoryContract.require(indexState != MemoryIndexState.INDEX_REMOVED
                || state == State.WITHDRAWN || state == State.DELETION_PENDING || state == State.DELETED,
                MemoryException.Field.STATE);
        MemoryContract.require(state != State.DELETED || payload == null && brief == null && committedRef == null, MemoryException.Field.EXPORT);
    }
}
