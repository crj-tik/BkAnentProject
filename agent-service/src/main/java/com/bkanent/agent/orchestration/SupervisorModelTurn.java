package com.bkanent.agent.orchestration;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/** One model turn. Definition-only callbacks make execution ownership explicit even for custom models. */
@Component
public class SupervisorModelTurn {
    private final ChatModel model;
    public SupervisorModelTurn(ChatModel model) { this.model = model; }

    public AssistantMessage call(List<Message> messages, List<ToolCallback> tools) {
        var definitions = tools.stream().map(tool -> (ToolCallback) new ToolCallback() {
            public ToolDefinition getToolDefinition() { return tool.getToolDefinition(); }
            public String call(String arguments) { throw new IllegalStateException("only ExecuteTool may invoke callbacks"); }
        }).toList();
        var options = ToolCallingChatOptions.builder().toolCallbacks(definitions).internalToolExecutionEnabled(false).build();
        var response = model.call(new Prompt(messages, options));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
            throw new IllegalStateException("MODEL_UNAVAILABLE: empty response");
        return response.getResult().getOutput();
    }
}
