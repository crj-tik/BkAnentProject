package com.bkanent.common.skill.runtime;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.bkanent.common.agent.SkillSelection;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillExecutionContextTest {
    private final SkillRegistry registry = mock(SkillRegistry.class);
    private final Map<String, String> tools = Map.of("local:search", "search", "local:write", "write");

    @Test
    void explicitSelectionPinsBodyAndBlocksSwitchAndActualOutOfScopeCallback() {
        when(registry.getByName("find")).thenReturn(skill(false));
        var execution = new SkillExecutionContext(registry, "listing", "预算300万", tools,
                new SkillSelection("find", "1"));
        when(registry.getByName("find")).thenReturn(SkillDefinition.builder().name("find").description("find").domain("listing")
                .version("2").tools(List.of("write")).systemPrompt("updated").build());
        assertThat(execution.activate(new SkillSelection("find", "1")).definition().systemPrompt()).isEqualTo("先理解预算再检索");
        assertThatThrownBy(() -> execution.activate(new SkillSelection("find", "2")))
                .isInstanceOf(SkillExecutionException.class);
        var request = new ToolCallRequest("write", "{}", "call-1", Map.of(SkillExecutionContext.KEY, execution));
        AtomicInteger effects = new AtomicInteger();
        var response = new SkillRoutingToolInterceptor().interceptToolCall(request, ignored -> {
            effects.incrementAndGet(); return null;
        });
        assertThat(response.isError()).isTrue();
        assertThat(response.toToolResponse().id()).isEqualTo("call-1");
        assertThat(effects).hasValue(0);
    }

    @Test
    void failedAndGuessedExplicitOnlyLoadsNeverActivate() {
        when(registry.getByName("find")).thenReturn(skill(true));
        var execution = new SkillExecutionContext(registry, "listing", "找房", tools, null);
        var interceptor = new SkillRoutingToolInterceptor();
        for (String name : List.of("find", "missing")) {
            var request = new ToolCallRequest("skill", "{\"name\":\"" + name + "\",\"task\":\"找房\"}",
                    "call-" + name, Map.of(SkillExecutionContext.KEY, execution));
            assertThat(interceptor.interceptToolCall(request, ignored -> { throw new AssertionError(); }).isError()).isTrue();
            assertThat(execution.snapshot()).isNull();
        }
    }

    @Test
    void realReactAgentCarriesRequestMetadataToBothGuards() throws Exception {
        when(registry.getByName("find")).thenReturn(skill(false));
        var execution = new SkillExecutionContext(registry, "listing", "预算300万", tools,
                new SkillSelection("find", "1"));
        ChatModel model = mock(ChatModel.class);
        AtomicInteger turns = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(invocation -> {
            var prompt = (org.springframework.ai.chat.prompt.Prompt) invocation.getArgument(0);
            assertThat(prompt.getSystemMessage().getText()).contains("预算300万", "先理解预算再检索");
            var message = turns.getAndIncrement() == 0
                    ? AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("write-call", "function", "write", "{}"))).build()
                    : new AssistantMessage("完成");
            return new ChatResponse(List.of(new Generation(message)));
        });
        ReactAgent agent = ReactAgent.builder().name("guard-test").model(model).systemPrompt("base")
                .tools(List.of(callback("search", effects), callback("write", effects), new SkillTool(registry, "listing")))
                .interceptors(new SkillRoutingModelInterceptor(registry, "listing"), new SkillRoutingToolInterceptor())
                .build();
        agent.invoke("找房", RunnableConfig.builder().addMetadata(SkillExecutionContext.KEY, execution).build());
        assertThat(effects).hasValue(0);
        assertThat(turns).hasValue(2);
    }

    private ToolCallback callback(String name, AtomicInteger effects) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(name)
                    .description(name).inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String arguments) { effects.incrementAndGet(); return "ok"; }
        };
    }

    private SkillDefinition skill(boolean explicitOnly) {
        return SkillDefinition.builder().name("find").description("find").domain("listing").tools(List.of("search"))
                .systemPrompt("先理解预算再检索").explicitOnly(explicitOnly).build();
    }
}
