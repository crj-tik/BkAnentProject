package com.bkanent.common.memory;

import java.io.Serializable;

/** Explicit owner decision; reactivation must cite existing positive verified evidence. */
public record MemoryExperienceLifecycleRequest(MemoryRequestEnvelope envelope, MemoryContextKey contextKey,
        String experienceId, long expectedRevision, Action action, String evidenceId, String reason)
        implements Serializable, MemoryV2Request {
    private static final long serialVersionUID = 1L;
    public enum Action { DEACTIVATE, REACTIVATE }

    public MemoryExperienceLifecycleRequest {
        MemoryV2Contract.requireOperation(envelope, MemoryV2Contract.OPERATION_EXPERIENCE_LIFECYCLE);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        experienceId = MemoryContract.id(experienceId, MemoryException.Field.SOURCE);
        MemoryContract.require(expectedRevision > 0, MemoryException.Field.VERSION);
        MemoryContract.required(action, MemoryException.Field.STATE);
        evidenceId = MemoryContract.optionalId(evidenceId, MemoryException.Field.EVIDENCE);
        reason = MemoryContract.text(reason, MemoryException.Field.TEXT);
        MemoryContract.require(reason.length() <= 1000, MemoryException.Field.TEXT);
        MemoryContract.require(action != Action.REACTIVATE || evidenceId != null, MemoryException.Field.EVIDENCE);
    }
}
