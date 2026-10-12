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
    @Tool(name = "milvusKnowledgeSearch", description = "Search the authorized managed knowledge space through remote RAG. Takes only a query; never accepts credentials, user identity or physical collection names.")
    public String searchKnowledge(String query) {
        AgentToolContextHolder.AgentToolContext context = AgentToolContextHolder.current();
        if (!context.isAllowKnowledgeSearch()) {
            String disabledMessage = "{\"status\":\"FAILED\",\"code\":\"KNOWLEDGE_SEARCH_DISABLED\",\"readOnly\":true}";
            AgentToolContextHolder.recordTool("milvusKnowledgeSearch", query, disabledMessage);
            return disabledMessage;
        }
        try {
            int topK = com.bkanent.agent.rag.AgentRagCompatibility.topK(context.getTopK(), agentChatProperties.getDefaultTopK());
            List<MilvusSearchResult> results = agentMemoryMilvusService.searchKnowledge(context.getUserId(), context.getCollectionName(), query, topK);
            AgentToolContextHolder.recordMilvus(query, results);
            String response = formatResults(results);
            if (results.stream().anyMatch(result -> Boolean.TRUE.equals(result.metadata().get("degraded"))))
                response = "[degraded=true] " + response;
            AgentToolContextHolder.recordTool("milvusKnowledgeSearch", query, response);
            return response;
        } catch (com.bkanent.common.rag.RagException exception) {
            return failure(query, exception.code().name());
        } catch (com.bkanent.agent.security.AgentRagAuthenticationException exception) {
            return failure(query, exception.code());
        }
    }

    /**
     * 格式化results。
     */
    private String failure(String query, String code) {
        String result = "{\"status\":\"FAILED\",\"code\":\"" + code + "\",\"readOnly\":true}";
        AgentToolContextHolder.recordMilvus(query, List.of());
        AgentToolContextHolder.recordTool("milvusKnowledgeSearch", query, result);
        return result;
    }

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
