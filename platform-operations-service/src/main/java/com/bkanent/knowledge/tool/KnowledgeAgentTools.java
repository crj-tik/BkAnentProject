package com.bkanent.knowledge.tool;

import com.bkanent.knowledge.api.GenerateKnowledgeRequest;
import com.bkanent.knowledge.api.KnowledgeGenerationStatus;
import com.bkanent.knowledge.service.KnowledgeGenerationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeAgentTools {
    private final KnowledgeGenerationService generation;

    public KnowledgeAgentTools(KnowledgeGenerationService generation) { this.generation = generation; }

    @Tool(description = "Start a knowledge candidate generation from published Interview asset IDs. Returns task status only; never returns transcript text or quotes.")
    public KnowledgeGenerationStatus startKnowledgeGeneration(
            @ToolParam(description = "Knowledge case ID") String knowledgeId,
            @ToolParam(description = "Stable operation request ID") String requestId,
            @ToolParam(description = "Expected Knowledge case version") long expectedVersion,
            @ToolParam(description = "1 to 20 published Interview asset IDs") List<String> sourceAssetIds,
            @ToolParam(description = "Rules version") String rulesVersion) {
        return generation.generate(knowledgeId,
                new GenerateKnowledgeRequest(requestId, expectedVersion, sourceAssetIds, rulesVersion));
    }

    @Tool(description = "Read a Knowledge generation task status. Returns IDs, state, revision, and error code only.")
    public KnowledgeGenerationStatus getKnowledgeGenerationStatus(
            @ToolParam(description = "Knowledge case ID") String knowledgeId,
            @ToolParam(description = "Generation task ID") String taskId) {
        return generation.status(knowledgeId, taskId);
    }
}
