package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Complete authorized message, not a streaming chunk or a framework Message. */
public record MemoryMessage(String messageId, MemoryContextKey contextKey, long sequence,
                            MemoryAssociation association, Role role, String text,
                            List<MemoryToolCall> toolCalls, List<MemoryToolResponse> toolResponses,
                            MemorySourceRef source, List<MemorySourceDependency> dependencies, MemoryMatterIntent matterIntent) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public MemoryMessage(String messageId, MemoryContextKey contextKey, long sequence, MemoryAssociation association, Role role,
                         String text, List<MemoryToolCall> calls, List<MemoryToolResponse> responses, MemorySourceRef source) {
        this(messageId, contextKey, sequence, association, role, text, calls, responses, source, List.of(), null);
    }

    public MemoryMessage(String messageId, MemoryContextKey contextKey, long sequence, MemoryAssociation association, Role role,
                         String text, List<MemoryToolCall> calls, List<MemoryToolResponse> responses, MemorySourceRef source, List<MemorySourceDependency> dependencies) {
        this(messageId, contextKey, sequence, association, role, text, calls, responses, source, dependencies, null);
    }

    public MemoryMessage {
        messageId = MemoryContract.id(messageId, MemoryException.Field.SOURCE);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.require(sequence > 0, MemoryException.Field.SOURCE);
        MemoryContract.required(association, MemoryException.Field.ASSOCIATION);
        MemoryContract.required(role, MemoryException.Field.ROLE);
        MemoryContract.require(matterIntent == null || role == Role.USER, MemoryException.Field.ROLE);
        text = MemoryContract.optionalText(text, MemoryException.Field.TEXT);
        toolCalls = MemoryContract.list(toolCalls, MemoryException.Field.TOOL_CALL);
        toolResponses = MemoryContract.list(toolResponses, MemoryException.Field.TOOL_RESPONSE);
        dependencies = MemoryContract.list(dependencies, MemoryException.Field.SOURCE);
        MemoryContract.require(toolCalls.stream().map(MemoryToolCall::callId).distinct().count() == toolCalls.size(), MemoryException.Field.TOOL_CALL);
        MemoryContract.require(toolResponses.stream().map(MemoryToolResponse::callId).distinct().count() == toolResponses.size(), MemoryException.Field.TOOL_RESPONSE);
        MemoryContract.require(role == Role.ASSISTANT || toolCalls.isEmpty(), MemoryException.Field.ROLE);
        MemoryContract.require(role == Role.TOOL ? toolCalls.isEmpty() && !toolResponses.isEmpty() : toolResponses.isEmpty(), MemoryException.Field.ROLE);
        MemoryContract.require(text != null && !text.isBlank() || !toolCalls.isEmpty() || !toolResponses.isEmpty(), MemoryException.Field.TEXT);
        MemoryContract.require(source != null && source.kind() == MemorySourceRef.Kind.MESSAGE
                && source.contextKey().equals(contextKey) && source.sourceId().equals(messageId)
                && Long.valueOf(sequence).equals(source.sourceSequence()) && source.sourceEventId() != null,
                MemoryException.Field.SOURCE);
    }
}
