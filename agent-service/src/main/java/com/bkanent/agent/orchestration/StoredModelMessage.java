package com.bkanent.agent.orchestration;

import org.springframework.ai.chat.messages.*;
import java.util.List;

/** Explicit wire representation avoids polymorphic Spring Message deserialization on restart. */
public record StoredModelMessage(String role, String text, List<AssistantMessage.ToolCall> calls,
                                 List<ToolResponseMessage.ToolResponse> responses,
                                 List<com.bkanent.common.memory.MemorySourceDependency> memoryDependencies) {
    public StoredModelMessage(String role, String text, List<AssistantMessage.ToolCall> calls, List<ToolResponseMessage.ToolResponse> responses) {
        this(role, text, calls, responses, List.of());
    }
    public StoredModelMessage { memoryDependencies = memoryDependencies == null ? List.of() : List.copyOf(memoryDependencies); }
    public static StoredModelMessage user(String text) { return new StoredModelMessage("user", text, List.of(), List.of()); }
    public static StoredModelMessage assistant(AssistantMessage message) { return new StoredModelMessage("assistant", message.getText(), message.getToolCalls(), List.of()); }
    public static StoredModelMessage tools(List<ToolResponseMessage.ToolResponse> responses) { return new StoredModelMessage("tool", "", List.of(), responses); }
    public static StoredModelMessage assistant(AssistantMessage message, List<com.bkanent.common.memory.MemorySourceDependency> sources) {
        return new StoredModelMessage("assistant", message.getText(), message.getToolCalls(), List.of(), sources);
    }
    public static StoredModelMessage tools(List<ToolResponseMessage.ToolResponse> responses, List<com.bkanent.common.memory.MemorySourceDependency> sources) {
        return new StoredModelMessage("tool", "", List.of(), responses, sources);
    }
    public Message message() {
        return switch (role) {
            case "user" -> new UserMessage(text);
            case "assistant" -> AssistantMessage.builder().content(text == null ? "" : text).toolCalls(calls).build();
            case "tool" -> ToolResponseMessage.builder().responses(responses).build();
            default -> throw new IllegalStateException("unknown stored message role");
        };
    }
}
