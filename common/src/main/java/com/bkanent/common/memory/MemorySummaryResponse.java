package com.bkanent.common.memory;

import java.io.Serializable;

/** Summary adoption receipt: durable identity plus the resulting conversation version when accepted. */
public record MemorySummaryResponse(String summaryId, boolean accepted, String reason,
                                     MemoryConversationVersion version) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemorySummaryResponse {
        summaryId = MemoryContract.id(summaryId, MemoryException.Field.SUMMARY);
        reason = MemoryContract.optionalText(reason, MemoryException.Field.TEXT);
        MemoryContract.required(version, MemoryException.Field.VERSION);
    }
}
