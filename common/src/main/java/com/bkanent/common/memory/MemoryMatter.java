package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Context lifecycle only; CLOSED requires attributed evidence, whose validity is a server decision. */
public record MemoryMatter(String matterId, MemoryContextKey contextKey, MemoryRevision version,
                           String goal, State state, ClosureEvidence closure,
                           List<MemorySourceDependency> dependencies) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum State { ACTIVE, CLOSED, REOPENED }
    public enum ClosureKind { USER_CONFIRMATION, VERIFIED_OUTCOME }
    public record ClosureEvidence(ClosureKind kind, List<MemorySourceDependency> evidence) implements Serializable {
        private static final long serialVersionUID = 1L;
        public ClosureEvidence {
            MemoryContract.required(kind, MemoryException.Field.EVIDENCE);
            evidence = MemoryContract.nonempty(evidence, MemoryException.Field.EVIDENCE);
        }
    }
    public MemoryMatter {
        matterId = MemoryContract.id(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.required(version, MemoryException.Field.VERSION);
        goal = MemoryContract.text(goal, MemoryException.Field.TEXT);
        MemoryContract.required(state, MemoryException.Field.STATE);
        dependencies = MemoryContract.list(dependencies, MemoryException.Field.SOURCE);
        MemoryContract.require(state != State.CLOSED || closure != null, MemoryException.Field.EVIDENCE);
        MemoryContract.require(closure == null || dependencies.containsAll(closure.evidence()), MemoryException.Field.EVIDENCE);
    }
}
