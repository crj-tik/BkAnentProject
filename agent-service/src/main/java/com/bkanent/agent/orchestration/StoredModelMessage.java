package com.bkanent.agent.orchestration;

import org.springframework.ai.chat.messages.*;
import java.util.List;

/** Explicit wire representation avoids polymorphic Spring Message deserialization on restart. */
public record StoredModelMessage(String role, String text, List<AssistantMessage.ToolCall> calls,
                                 List<ToolResponseMessage.ToolResponse> responses,
                                 List<com.bkanent.common.memory.MemorySourceDependency> memoryDependencies,
                                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String matterId,
                                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) com.bkanent.common.memory.MemoryMatterIntent matterIntent) {
    public StoredModelMessage(String role,String text,List<AssistantMessage.ToolCall> calls,List<ToolResponseMessage.ToolResponse> responses,
            List<com.bkanent.common.memory.MemorySourceDependency> memoryDependencies) {
        this(role,text,calls,responses,memoryDependencies,null,null);
    }
    public StoredModelMessage(String role, String text, List<AssistantMessage.ToolCall> calls, List<ToolResponseMessage.ToolResponse> responses) {
        this(role, text, calls, responses, List.of());
    }
    public StoredModelMessage { memoryDependencies = memoryDependencies == null ? List.of() : List.copyOf(memoryDependencies); }
    public static StoredModelMessage user(String text) { return new StoredModelMessage("user", text, List.of(), List.of()); }
    public static StoredModelMessage user(String text,String matterId,com.bkanent.common.memory.MemoryMatterIntent intent) {
        String association = intent == null ? matterId : intent.matterId();
        return new StoredModelMessage("user",text,List.of(),List.of(),List.of(),association,intent);
    }
    public StoredModelMessage inMatter(String id) { return new StoredModelMessage(role,text,calls,responses,memoryDependencies,id,matterIntent); }
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
