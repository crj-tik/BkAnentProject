package com.bkanent.common.memory;

import java.io.Serializable;

/** Current safe projection text paired with its stable export revision. */
public record MemoryCollaborationMatch(MemoryExportRef exportRef, String content,
        Double relevanceScore) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryCollaborationMatch {
        MemoryContract.required(exportRef, MemoryException.Field.EXPORT);
        content = MemoryContract.text(content, MemoryException.Field.TEXT);
        if (relevanceScore != null) MemoryContract.unit(relevanceScore);
    }

    @Override public String toString() { return "MemoryCollaborationMatch[REDACTED]"; }
}
