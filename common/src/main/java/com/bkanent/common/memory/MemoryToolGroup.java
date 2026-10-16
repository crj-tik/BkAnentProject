package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** All response envelopes are present; their states may still be waiting, failed or unknown. */
public record MemoryToolGroup(MemoryMessage assistant, List<MemoryMessage> responses) implements Serializable {
    private static final long serialVersionUID = 1L;
    public MemoryToolGroup {
        MemoryContract.require(assistant != null && assistant.role() == MemoryMessage.Role.ASSISTANT
                && !assistant.toolCalls().isEmpty(), MemoryException.Field.TOOL_GROUP);
        responses = MemoryContract.nonempty(responses, MemoryException.Field.TOOL_GROUP);
        Map<String, String> pending = new HashMap<>();
        assistant.toolCalls().forEach(call -> pending.put(call.callId(), call.toolName()));
        long previous = assistant.sequence();
        for (MemoryMessage response : responses) {
            MemoryContract.require(response.role() == MemoryMessage.Role.TOOL
                    && response.contextKey().equals(assistant.contextKey())
                    && response.association().equals(assistant.association()) && response.sequence() > previous,
                    MemoryException.Field.TOOL_GROUP);
            previous = response.sequence();
            for (MemoryToolResponse tool : response.toolResponses()) {
                String expectedName = pending.remove(tool.callId());
                MemoryContract.require(tool.toolName().equals(expectedName), MemoryException.Field.TOOL_GROUP);
            }
        }
        if (!pending.isEmpty()) throw new MemoryException(MemoryErrorCode.INCOMPLETE_TOOL_GROUP, MemoryException.Field.TOOL_GROUP);
    }
    public MemoryCoveredRange range() {
        return new MemoryCoveredRange(assistant.sequence(), responses.get(responses.size() - 1).sequence());
    }
}
