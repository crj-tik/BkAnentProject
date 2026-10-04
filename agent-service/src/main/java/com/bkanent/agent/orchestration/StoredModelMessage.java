package com.bkanent.agent.orchestration;

import org.springframework.ai.chat.messages.*;
import java.util.List;

/** Explicit wire representation avoids polymorphic Spring Message deserialization on restart. */
public record StoredModelMessage(String role, String text, List<AssistantMessage.ToolCall> calls,
                                 List<ToolResponseMessage.ToolResponse> responses) {
    public static StoredModelMessage user(String text) { return new StoredModelMessage("user", text, List.of(), List.of()); }
    public static StoredModelMessage assistant(AssistantMessage message) { return new StoredModelMessage("assistant", message.getText(), message.getToolCalls(), List.of()); }
    public static StoredModelMessage tools(List<ToolResponseMessage.ToolResponse> responses) { return new StoredModelMessage("tool", "", List.of(), responses); }
    public Message message() {
        return switch (role) {
            case "user" -> new UserMessage(text);
            case "assistant" -> AssistantMessage.builder().content(text == null ? "" : text).toolCalls(calls).build();
            case "tool" -> ToolResponseMessage.builder().responses(responses).build();
            default -> throw new IllegalStateException("unknown stored message role");
        };
    }
}
