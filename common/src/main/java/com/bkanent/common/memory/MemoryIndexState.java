package com.bkanent.common.memory;

/** Index readiness is independent of durable truth-source readability. */
public enum MemoryIndexState { NOT_REQUESTED, INDEX_PENDING, INDEX_READY, INDEX_FAILED, INDEX_REMOVED }
