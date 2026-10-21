package com.bkanent.common.memory;

import java.io.Serializable;

public record MemoryExperienceLifecycleResponse(String experienceId, long revision, MemoryState state,
        String reason) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryExperienceLifecycleResponse {
        experienceId = MemoryContract.id(experienceId, MemoryException.Field.SOURCE);
        MemoryContract.require(revision > 0, MemoryException.Field.VERSION);
        MemoryContract.required(state, MemoryException.Field.STATE);
        reason = MemoryContract.text(reason, MemoryException.Field.TEXT);
    }
}
