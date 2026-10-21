package com.bkanent.memory.service;

import com.bkanent.common.memory.MemoryContextKey;

/** Idempotently removes one exact source event from one managed external projection. */
public interface MemoryPrivacyProjectionCleaner {
    String projectionId();

    void remove(MemoryContextKey contextKey, String sourceEventId, String sourceHash);
}
