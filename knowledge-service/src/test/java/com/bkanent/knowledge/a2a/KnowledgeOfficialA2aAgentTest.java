package com.bkanent.knowledge.a2a;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.bkanent.common.a2a.A2aExecutionProperties;
import com.bkanent.common.a2a.A2aMemoryAgentHook;
import com.bkanent.common.a2a.A2aMemoryHookFactory;
import com.bkanent.common.a2a.OfficialA2aAgentExecutor;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.knowledge.service.KnowledgeGenerationService;
import com.bkanent.knowledge.tool.KnowledgeAgentTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeOfficialA2aAgentTest {
    @Test
    void requiredMemoryHookIsInstalledAfterTheInputGuardAndReusedByOfficialExecutor() throws Exception {
        A2aMemoryHookFactory factory = mock(A2aMemoryHookFactory.class);
        A2aMemoryAgentHook memoryHook = mock(A2aMemoryAgentHook.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(factory.create("knowledge-service", "knowledge", "knowledge-agent", chatModel)).thenReturn(memoryHook);
        SkillRegistry skills = mock(SkillRegistry.class);
        var component = new KnowledgeOfficialA2aAgent(chatModel,
                new KnowledgeAgentTools(mock(KnowledgeGenerationService.class)), skills, factory, new ObjectMapper());
        ReactAgent agent = component.knowledgeReactAgent();

        Field hooksField = ReactAgent.class.getDeclaredField("hooks");
        hooksField.setAccessible(true);
        List<?> hooks = (List<?>) hooksField.get(agent);
        assertThat(hooks).hasSize(2);
        assertThat(hooks.get(0)).isInstanceOf(KnowledgeInputGuardHook.class);
        assertThat(hooks.get(1)).isSameAs(memoryHook);

        var executor = component.knowledgeA2aAgentExecutor(new ObjectMapper(), new A2aExecutionProperties());
        Field memoryField = OfficialA2aAgentExecutor.class.getDeclaredField("memoryHook");
        memoryField.setAccessible(true);
        assertThat(memoryField.get(executor)).isSameAs(memoryHook);
    }
}
