package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/** Sanitized, recoverable processing receipt. */
public record MemoryExperienceEventStatusResponse(String evidenceId, State state, int attempts,
        String lastError, Instant acceptedAt, Instant updatedAt) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum State { PENDING, PROCESSING, RETRY, SUCCEEDED, FAILED, CANCELLED, WITHDRAWN, DELETED }
    public MemoryExperienceEventStatusResponse {
        evidenceId = MemoryContract.id(evidenceId, MemoryException.Field.EVIDENCE);
        MemoryContract.required(state, MemoryException.Field.STATE);
        MemoryContract.require(attempts >= 0, MemoryException.Field.VERSION);
        lastError = MemoryContract.optionalId(lastError, MemoryException.Field.STATE);
        MemoryContract.required(acceptedAt, MemoryException.Field.EVIDENCE);
        MemoryContract.required(updatedAt, MemoryException.Field.EVIDENCE);
    }
}
