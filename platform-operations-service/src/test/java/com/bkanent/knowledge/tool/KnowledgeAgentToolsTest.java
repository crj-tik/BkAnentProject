package com.bkanent.knowledge.tool;

import com.bkanent.knowledge.api.GenerateKnowledgeRequest;
import com.bkanent.knowledge.api.KnowledgeGenerationStatus;
import com.bkanent.knowledge.service.KnowledgeGenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAgentToolsTest {
    @Test
    void toolArgumentsAndResponsesContainOnlyReferencesAndTaskStatus() throws Exception {
        KnowledgeGenerationService generation = mock(KnowledgeGenerationService.class);
        KnowledgeAgentTools tools = new KnowledgeAgentTools(generation);
        KnowledgeGenerationStatus status = new KnowledgeGenerationStatus("task-1", "knowledge-1",
                "SUCCEEDED", "r2", null);
        when(generation.generate(eq("knowledge-1"), any(GenerateKnowledgeRequest.class))).thenReturn(status);
        String transcriptSentinel = "哨兵逐字稿原文和原声";

        KnowledgeGenerationStatus result = tools.startKnowledgeGeneration("knowledge-1", "request-1", 3,
                List.of("101"), "rules-v1");

        verify(generation).generate("knowledge-1",
                new GenerateKnowledgeRequest("request-1", 3L, List.of("101"), "rules-v1"));
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain(transcriptSentinel)
                .contains("task-1", "SUCCEEDED", "r2");
    }
}
