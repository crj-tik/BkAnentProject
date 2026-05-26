package com.bkanent.agent.service;

import com.bkanent.agent.config.AgentChatProperties;
import com.bkanent.agent.model.chat.AgentChatRequest;
import com.bkanent.agent.model.chat.AgentChatResponse;
import com.bkanent.agent.model.chat.AgentToolDecision;
import com.bkanent.agent.tool.context.AgentToolContextHolder;
import com.bkanent.agent.tool.context.AgentToolSessionSnapshot;
import org.springframework.stereotype.Service;

/**
 * AgentOrchestratorService 服务类。
 */
@Service
public class AgentOrchestratorService {

    /**
     * 字段：deepSeekChatService。
     */
    private final AgentChatService agentChatService;
    /**
     * 字段：agentDeepSeekProperties。
     */
    private final AgentChatProperties agentChatProperties;

    /**
     * 构造 AgentOrchestratorService 实例。
     */
    public AgentOrchestratorService(AgentChatService agentChatService,
                                    AgentChatProperties agentChatProperties) {
        this.agentChatService = agentChatService;
        this.agentChatProperties = agentChatProperties;
    }

    /**
     * 处理对话。
     */
    public AgentChatResponse chat(AgentChatRequest request) {
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }

        int topK = request.topK() == null ? agentChatProperties.getDefaultTopK() : Math.max(1, request.topK());
        boolean allowMcp = request.allowMcp() == null || request.allowMcp();

        AgentToolContextHolder.init(request.collectionName(), topK, true);
        try {
            String answer = agentChatService.call(
                    agentChatService.getSystemPrompt(),
                    buildUserPrompt(message, request.collectionName(), topK, allowMcp),
                    allowMcp
            );
            AgentToolSessionSnapshot snapshot = AgentToolContextHolder.snapshot();
            return new AgentChatResponse(
                    answer,
                    agentChatService.getModel(),
                    buildDecision(snapshot),
                    snapshot.milvusResults(),
                    snapshot.toolContext()
            );
        } finally {
            AgentToolContextHolder.clear();
        }
    }

    /**
     * 构建userPrompt。
     */
    private String buildUserPrompt(String message, String collectionName, int topK, boolean allowMcp) {
        return """
                User question:
                %s

                Current session context:
                - Knowledge collection: %s
                - Default search topK: %s
                - MCP tools allowed: %s

                Decide whether tools are needed first, then answer based on real tool results.
                """.formatted(
                message,
                collectionName == null || collectionName.isBlank() ? "agent_knowledge" : collectionName,
                topK,
                allowMcp ? "yes" : "no"
        ).trim();
    }

    /**
     * 构建decision。
     */
    private AgentToolDecision buildDecision(AgentToolSessionSnapshot snapshot) {
        if (!snapshot.usedTool()) {
            return new AgentToolDecision(false, null, null, null, "Model decided no tool call was needed");
        }
        boolean usedKnowledgeTool = snapshot.milvusResults() != null && !snapshot.milvusResults().isEmpty();
        String reason = usedKnowledgeTool
                ? "Model invoked the Milvus knowledge retrieval tool"
                : "Model invoked an external MCP tool";
        return new AgentToolDecision(usedKnowledgeTool, snapshot.firstToolName(), snapshot.firstToolQuery(), snapshot.topK(), reason);
    }
}
