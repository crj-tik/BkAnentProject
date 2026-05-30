package com.bkanent.agent.tool;

import com.bkanent.agent.config.AgentChatProperties;
import com.bkanent.agent.milvus.core.model.MilvusSearchResult;
import com.bkanent.agent.milvus.memory.AgentMemoryMilvusService;
import com.bkanent.agent.tool.context.AgentToolContextHolder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentMilvusTool implements AgentTool {

    /**
     * 字段：agentMemoryMilvusService。
     */
    private final AgentMemoryMilvusService agentMemoryMilvusService;
    /**
     * 字段：agentDeepSeekProperties。
     */
    private final AgentChatProperties agentChatProperties;

    /**
     * 构造 AgentMilvusTool 实例。
     */
    public AgentMilvusTool(AgentMemoryMilvusService agentMemoryMilvusService,
                           AgentChatProperties agentChatProperties) {
        this.agentMemoryMilvusService = agentMemoryMilvusService;
        this.agentChatProperties = agentChatProperties;
    }

    /**
     * 检索knowledge。
     */
    @Tool(name = "milvusKnowledgeSearch", description = "Search relevant content from the Milvus knowledge base.")
    public String searchKnowledge(String query) {
        AgentToolContextHolder.AgentToolContext context = AgentToolContextHolder.current();
        if (!context.isAllowKnowledgeSearch()) {
            String disabledMessage = "Knowledge search is disabled for the current session.";
            AgentToolContextHolder.recordTool("milvusKnowledgeSearch", query, disabledMessage);
            return disabledMessage;
        }
        int topK = context.getTopK() == null ? agentChatProperties.getDefaultTopK() : Math.max(1, context.getTopK());
        List<MilvusSearchResult> results = agentMemoryMilvusService.searchKnowledge(context.getCollectionName(), query, topK);
        AgentToolContextHolder.recordMilvus(query, results);
        String response = formatResults(results);
        AgentToolContextHolder.recordTool("milvusKnowledgeSearch", query, response);
        return response;
    }

    /**
     * 格式化results。
     */
    private String formatResults(List<MilvusSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "No relevant content was found in the knowledge base.";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < results.size(); index++) {
            MilvusSearchResult result = results.get(index);
            builder.append(index + 1)
                    .append(". score=")
                    .append(result.score())
                    .append(", content=")
                    .append(result.content())
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }
}
