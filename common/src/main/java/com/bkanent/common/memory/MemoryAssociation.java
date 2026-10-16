package com.bkanent.common.memory;

import java.io.Serializable;

/** Nullable independent associations. None substitutes for MemoryContextKey or authorizes a call. */
public record MemoryAssociation(String matterId, String runId, String a2aTaskId, DomainSessionRef domainSessionRef)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryAssociation {
        matterId = MemoryContract.optionalId(matterId, MemoryException.Field.ASSOCIATION);
        runId = MemoryContract.optionalId(runId, MemoryException.Field.ASSOCIATION);
        a2aTaskId = MemoryContract.optionalId(a2aTaskId, MemoryException.Field.ASSOCIATION);
    }

    public record DomainSessionRef(String domainId, String sessionId) implements Serializable {
        private static final long serialVersionUID = 1L;
        public DomainSessionRef {
            domainId = MemoryContract.id(domainId, MemoryException.Field.ASSOCIATION);
            sessionId = MemoryContract.id(sessionId, MemoryException.Field.ASSOCIATION);
        }
    }
}
