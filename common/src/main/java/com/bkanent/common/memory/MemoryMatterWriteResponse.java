package com.bkanent.common.memory;

import java.io.Serializable;

public record MemoryMatterWriteResponse(MemoryMatter matter, MemoryConversationVersion version, boolean applied) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryMatterWriteResponse {
        MemoryContract.required(matter, MemoryException.Field.ASSOCIATION);
        MemoryContract.required(version, MemoryException.Field.VERSION);
    }
}
