package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Concise display text never replaces complete authorized responseData or its versioned full source. */
public record MemoryToolResponse(String callId, String toolName, ExecutionState state, String text,
                                 DataForm dataForm, MemoryJsonValue responseData, MemorySourceRef fullDataRef,
                                 List<MemorySourceDependency> dependencies) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum ExecutionState { SUCCEEDED, FAILED, WAITING_APPROVAL, NOT_EXECUTED, ACCEPTED, PARTIAL, OUTCOME_UNKNOWN }
    public enum DataForm { INLINE_COMPLETE, REFERENCED_COMPLETE }

    public MemoryToolResponse {
        callId = MemoryContract.id(callId, MemoryException.Field.TOOL_RESPONSE);
        toolName = MemoryContract.id(toolName, MemoryException.Field.TOOL_RESPONSE);
        MemoryContract.required(state, MemoryException.Field.STATE);
        text = MemoryContract.optionalText(text, MemoryException.Field.TEXT);
        MemoryContract.required(dataForm, MemoryException.Field.TOOL_RESPONSE);
        dependencies = MemoryContract.list(dependencies, MemoryException.Field.SOURCE);
        MemoryContract.require(dataForm == DataForm.INLINE_COMPLETE
                ? responseData != null && fullDataRef == null
                : fullDataRef != null && dependencies.stream().anyMatch(d -> d.source().equals(fullDataRef)),
                MemoryException.Field.TOOL_RESPONSE);
        // In REFERENCED_COMPLETE form responseData, if present, is only a bounded projection.
    }
}
