package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/** Safe lifecycle result for a private caller-held copy; it never returns aggregate content. */
public record MemoryDomainExperienceUseCheckResponse(String aggregateId, long requestedRevision,
        long currentRevision, State state, Instant checkedAt) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum State { CURRENT, REVISED, WITHDRAWN, EXPIRED, MISSING }

    public MemoryDomainExperienceUseCheckResponse {
        MemoryContract.require(aggregateId != null && aggregateId.matches("dge_[A-Za-z0-9._:%-]{1,120}"),
                MemoryException.Field.IDENTITY);
        MemoryContract.require(requestedRevision > 0 && currentRevision >= 0, MemoryException.Field.VERSION);
        MemoryContract.required(state, MemoryException.Field.STATE);
        MemoryContract.required(checkedAt, MemoryException.Field.EVIDENCE);
        MemoryContract.require((currentRevision == 0) == (state == State.MISSING), MemoryException.Field.STATE);
    }
}
