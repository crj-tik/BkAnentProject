package com.bkanent.common.memory;

import java.io.Serializable;

/** Tool identity and complete argument structure; target is a logical capability, never an endpoint. */
public record MemoryToolCall(String callId, String capabilityId, String toolName, MemoryJsonValue arguments)
        implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryToolCall {
        callId = MemoryContract.id(callId, MemoryException.Field.TOOL_CALL);
        capabilityId = MemoryContract.id(capabilityId, MemoryException.Field.TOOL_CALL);
        toolName = MemoryContract.id(toolName, MemoryException.Field.TOOL_CALL);
        MemoryContract.require(arguments != null && arguments.type() == MemoryJsonValue.Type.OBJECT, MemoryException.Field.TOOL_CALL);
    }
}
