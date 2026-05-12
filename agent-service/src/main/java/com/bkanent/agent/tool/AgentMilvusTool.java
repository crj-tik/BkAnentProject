package com.bkanent.agent.tool;

import com.bkanent.agent.config.AgentDeepSeekProperties;
import com.bkanent.agent.model.vector.MilvusSearchResult;
import com.bkanent.agent.planner.annotation.AgentPlannerAction;
import com.bkanent.agent.planner.annotation.AgentPlannerParam;
import com.bkanent.agent.tool.annotation.AgentBaseTool;
import com.bkanent.agent.tool.context.AgentToolContextHolder;
import com.bkanent.agent.vector.MilvusVectorStoreTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Milvus 知识检索工具。
 */
@Component
@AgentBaseTool
public class AgentMilvusTool implements AgentPlannerTool {

    private final MilvusVectorStoreTool milvusVectorStoreTool;
    private final AgentDeepSeekProperties agentDeepSeekProperties;

    public AgentMilvusTool(MilvusVectorStoreTool milvusVectorStoreTool,
                           AgentDeepSeekProperties agentDeepSeekProperties) {
        this.milvusVectorStoreTool = milvusVectorStoreTool;
        this.agentDeepSeekProperties = agentDeepSeekProperties;
    }

    @Tool(name = "milvusKnowledgeSearch", description = "从 Milvus 向量知识库中检索与用户问题相关的内容。")
    @AgentPlannerAction(
            action = "SEARCH_KNOWLEDGE",
            inputDescription = "从用户问题中提炼出的检索关键词。",
            outputDescription = "Milvus 检索得到的文本结果。",
            requiredArguments = {"query"},
            exampleArguments = "{\"query\":\"广州天河区 学区房 三居室\"}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}",
            outputSchema = "{\"type\":\"object\",\"properties\":{\"resultText\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"}},\"required\":[\"resultText\",\"query\"]}",
            exampleOutput = "{\"resultText\":\"1. 得分 0.92，内容：广州天河区三居室学区房，近地铁。\",\"query\":\"广州天河区 学区房 三居室\"}"
    )
    public String searchKnowledge(@AgentPlannerParam("query") String query) {
        AgentToolContextHolder.AgentToolContext context = AgentToolContextHolder.current();
        if (!context.isAllowKnowledgeSearch()) {
            String disabledMessage = "当前会话未开启知识检索能力。";
            AgentToolContextHolder.recordTool("milvusKnowledgeSearch", query, disabledMessage);
            return disabledMessage;
        }
        int topK = context.getTopK() == null ? agentDeepSeekProperties.getDefaultTopK() : Math.max(1, context.getTopK());
        List<MilvusSearchResult> results = milvusVectorStoreTool.search(context.getCollectionName(), query, topK);
        AgentToolContextHolder.recordMilvus(query, results);
        String response = formatResults(results);
        AgentToolContextHolder.recordTool("milvusKnowledgeSearch", query, response);
        return response;
    }

    private String formatResults(List<MilvusSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "知识库未检索到相关内容。";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < results.size(); index++) {
            MilvusSearchResult result = results.get(index);
            builder.append(index + 1)
                    .append(". 得分 ")
                    .append(result.score())
                    .append("，内容：")
                    .append(result.content())
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }
}
