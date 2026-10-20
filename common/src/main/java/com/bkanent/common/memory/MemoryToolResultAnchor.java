package com.bkanent.common.memory;

import java.io.Serializable;

/** Versioned location of a complete archived response, never a read permission. */
public record MemoryToolResultAnchor(MemorySourceRef source, String callId, String toolName,
                                     MemoryToolResponse.ExecutionState state) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryToolResultAnchor {
        MemoryContract.required(source, MemoryException.Field.SOURCE);
        callId = MemoryContract.id(callId, MemoryException.Field.TOOL_RESPONSE);
        toolName = MemoryContract.id(toolName, MemoryException.Field.TOOL_RESPONSE);
        MemoryContract.required(state, MemoryException.Field.STATE);
    }
}
