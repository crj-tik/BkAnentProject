package com.bkanent.common.memory;

import java.io.Serializable;
import java.time.Instant;

/** Contains lifecycle metadata only; no archived or derived body is returned. */
public record MemoryPrivacyCleanupStatusResponse(MemoryContextKey contextKey,
                                                 String sourceEventId,
                                                 MemoryState sourceState,
                                                 MemoryPrivacyCleanupState cleanupState,
                                                 int attempts,
                                                 String lastError,
                                                 Instant updatedAt,
                                                 Instant completedAt) implements Serializable {
    public MemoryPrivacyCleanupStatusResponse {
        MemoryContract.required(contextKey, MemoryException.Field.ASSOCIATION);
        sourceEventId = MemoryContract.id(sourceEventId, MemoryException.Field.SOURCE);
        MemoryContract.required(sourceState, MemoryException.Field.STATE);
        MemoryContract.required(cleanupState, MemoryException.Field.STATE);
        MemoryContract.require(attempts >= 0, MemoryException.Field.VERSION);
        if (lastError != null) MemoryContract.require(lastError.matches("[A-Z0-9_]{1,64}"), MemoryException.Field.STATE);
        MemoryContract.require((cleanupState == MemoryPrivacyCleanupState.NOT_REQUESTED) == (updatedAt == null),
                MemoryException.Field.VERSION);
        MemoryContract.require(cleanupState == MemoryPrivacyCleanupState.COMPLETE || completedAt == null,
                MemoryException.Field.STATE);
    }
}
