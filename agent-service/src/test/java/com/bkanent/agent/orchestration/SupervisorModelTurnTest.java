package com.bkanent.agent.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupervisorModelTurnTest {
    @Test
    void actualSpringAiOptionsDisableInternalExecutionAndExposeOnlyDefinitions() {
        AtomicInteger effects = new AtomicInteger();
        ToolCallback tool = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("effect")
                    .description("real description").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { effects.incrementAndGet(); return "called"; }
        };
        var output = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("id-1", "function", "effect", "{}"))).build();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
            assertThat(options.getToolCallbacks().get(0).getToolDefinition()).isEqualTo(tool.getToolDefinition());
            assertThatThrownBy(() -> options.getToolCallbacks().get(0).call("{}"))
                    .isInstanceOf(IllegalStateException.class);
            return new ChatResponse(List.of(new Generation(output)));
        });
        assertThat(new SupervisorModelTurn(model).call(List.of(new UserMessage("execute")), List.of(tool))).isEqualTo(output);
        assertThat(effects).hasValue(0);
    }
}
