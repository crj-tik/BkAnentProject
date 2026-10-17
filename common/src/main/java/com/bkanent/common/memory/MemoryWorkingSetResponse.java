package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Cold-start assembled material: current summaries, recent messages, matters. Not an authorization proof. */
public record MemoryWorkingSetResponse(MemoryContextKey contextKey, MemoryConversationVersion version,
                                        List<MemoryMessage> messages, List<MemorySummary> summaries,
                                        List<MemoryMatter> matters) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryWorkingSetResponse {
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.required(version, MemoryException.Field.VERSION);
        messages = MemoryContract.list(messages, MemoryException.Field.ROLE);
        summaries = MemoryContract.list(summaries, MemoryException.Field.SUMMARY);
        matters = MemoryContract.list(matters, MemoryException.Field.ASSOCIATION);
    }
}
