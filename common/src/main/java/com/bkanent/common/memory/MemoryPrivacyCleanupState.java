package com.bkanent.common.memory;

/** Durable progress of privacy suppression and downstream projection cleanup. */
public enum MemoryPrivacyCleanupState {
    NOT_REQUESTED, PENDING, PROCESSING, RETRY, COMPLETE, FAILED
}
