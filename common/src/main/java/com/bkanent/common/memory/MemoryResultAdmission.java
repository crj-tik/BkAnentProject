package com.bkanent.common.memory;

import java.io.Serializable;

/** A single later result of a durably accepted invocation, never a model-proposed call. */
public record MemoryResultAdmission(MemorySourceRef acceptedSource, String callId, String resultEventId,
                                     String resultMessageId, long resultSequence) implements Serializable {
    public MemoryResultAdmission {
        MemoryContract.required(acceptedSource, MemoryException.Field.SOURCE);
        callId = MemoryContract.id(callId, MemoryException.Field.TOOL_CALL);
        resultEventId = MemoryContract.id(resultEventId, MemoryException.Field.SOURCE);
        resultMessageId = MemoryContract.id(resultMessageId, MemoryException.Field.SOURCE);
        MemoryContract.require(resultSequence > 0, MemoryException.Field.SOURCE);
    }
}
