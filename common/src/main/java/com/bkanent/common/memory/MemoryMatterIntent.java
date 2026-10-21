package com.bkanent.common.memory;

import java.io.Serializable;

/** Explicit user context operation. It never grants business, approval or cross-context authority. */
public record MemoryMatterIntent(Action action, String matterId, long expectedRevision, String goal) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Action { START, CONTINUE, CLOSE, REOPEN }
    public MemoryMatterIntent {
        MemoryContract.required(action, MemoryException.Field.STATE);
        if (action == Action.START) {
            MemoryContract.require(matterId == null && expectedRevision == 0, MemoryException.Field.ASSOCIATION);
            goal = MemoryContract.text(goal, MemoryException.Field.TEXT);
            MemoryContract.require(goal.length() <= 2000, MemoryException.Field.TEXT);
        } else {
            matterId = MemoryContract.id(matterId, MemoryException.Field.ASSOCIATION);
            MemoryContract.require(expectedRevision > 0 && goal == null, MemoryException.Field.VERSION);
        }
    }
}
