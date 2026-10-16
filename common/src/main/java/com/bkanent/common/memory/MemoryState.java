package com.bkanent.common.memory;

/** Lifecycle values describe truth-source state, never a business workflow or an access decision. */
public enum MemoryState { ACTIVE, INACTIVE, WITHDRAWN, DELETION_PENDING, DELETED }
