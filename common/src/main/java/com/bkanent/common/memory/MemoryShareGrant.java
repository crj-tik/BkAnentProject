package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/** Describes a persisted grant, not a signed permission or a substitute for current authorization. */
public record MemoryShareGrant(MemoryGrantRef grant, MemoryContextKey sourceContext,
                               MemoryScope scope, String matterId, String purpose,
                               List<Participant> recipients, List<Projection> projections,
                               List<MemorySourceRef> sources, Instant validFrom, Instant validUntil,
                               State state) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum State { ACTIVE, REVOKED, EXPIRED }
    public enum Projection { DISPLAY, COLLABORATION, DURABLE_EXPORT }
    public record Participant(String domainId, String agentId) implements Serializable {
        private static final long serialVersionUID = 1L;
        public Participant {
            domainId = MemoryContract.id(domainId, MemoryException.Field.GRANT);
            agentId = MemoryContract.id(agentId, MemoryException.Field.GRANT);
        }
    }
    public MemoryShareGrant {
        MemoryContract.required(grant, MemoryException.Field.GRANT);
        MemoryContract.required(sourceContext, MemoryException.Field.IDENTITY);
        MemoryContract.require(scope != null && scope.kind() != MemoryScope.Kind.DOMAIN_PRIVATE, MemoryException.Field.SCOPE);
        matterId = MemoryContract.optionalId(matterId, MemoryException.Field.ASSOCIATION);
        MemoryContract.require(scope.kind() != MemoryScope.Kind.TASK_SHARED || matterId != null, MemoryException.Field.GRANT);
        purpose = MemoryContract.id(purpose, MemoryException.Field.GRANT);
        recipients = MemoryContract.unique(recipients, MemoryException.Field.GRANT);
        projections = MemoryContract.unique(projections, MemoryException.Field.GRANT);
        sources = MemoryContract.unique(sources, MemoryException.Field.SOURCE);
        MemoryContract.require(!recipients.isEmpty() && !projections.isEmpty() && !sources.isEmpty(), MemoryException.Field.GRANT);
        for (MemorySourceRef source : sources) MemoryContract.require(source.contextKey().equals(sourceContext), MemoryException.Field.SOURCE);
        MemoryContract.required(validFrom, MemoryException.Field.GRANT);
        MemoryContract.required(validUntil, MemoryException.Field.GRANT);
        MemoryContract.interval(validFrom, validUntil, MemoryException.Field.GRANT);
        MemoryContract.required(state, MemoryException.Field.STATE);
    }
}
