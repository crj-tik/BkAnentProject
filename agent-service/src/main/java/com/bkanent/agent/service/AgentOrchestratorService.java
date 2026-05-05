package com.bkanent.agent.service;

import com.bkanent.agent.config.AgentQwenProperties;
import com.bkanent.agent.mcp.MilvusMcpTool;
import com.bkanent.agent.mcp.MilvusSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentOrchestratorService {

    private static final String ANSWER_SYSTEM_PROMPT = """
            You are a real-estate middle-platform agent.
            Answer accurately and concisely for business users.
            If MCP context is provided, prioritize that context and do not invent facts.
            If context is insufficient, say so clearly and provide the next step.
            """;

    private final AgentMcpDecisionService agentMcpDecisionService;
    private final MilvusMcpTool milvusMcpTool;
    private final QwenChatService qwenChatService;
    private final AgentQwenProperties agentQwenProperties;

    public AgentOrchestratorService(AgentMcpDecisionService agentMcpDecisionService,
                                    MilvusMcpTool milvusMcpTool,
                                    QwenChatService qwenChatService,
                                    AgentQwenProperties agentQwenProperties) {
        this.agentMcpDecisionService = agentMcpDecisionService;
        this.milvusMcpTool = milvusMcpTool;
        this.qwenChatService = qwenChatService;
        this.agentQwenProperties = agentQwenProperties;
    }

    public AgentChatResponse chat(AgentChatRequest request) {
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }

        boolean allowMcp = request.allowMcp() == null || request.allowMcp();
        AgentMcpDecision decision = agentMcpDecisionService.decide(message, allowMcp);
        List<MilvusSearchResult> toolResults = decision.useMcp()
                ? milvusMcpTool.search(request.collectionName(), decision.query(), resolveTopK(request.topK(), decision.topK()))
                : List.of();
        String toolContext = buildToolContext(toolResults);
        String answer = qwenChatService.complete(ANSWER_SYSTEM_PROMPT, buildAnswerUserPrompt(message, toolContext));
        return new AgentChatResponse(answer, qwenChatService.getModel(), decision, toolResults, toolContext);
    }

    private int resolveTopK(Integer requestTopK, Integer decisionTopK) {
        if (requestTopK != null) {
            return Math.max(1, requestTopK);
        }
        if (decisionTopK != null) {
            return Math.max(1, decisionTopK);
        }
        return agentQwenProperties.getDefaultTopK();
    }

    private String buildToolContext(List<MilvusSearchResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < toolResults.size(); index++) {
            MilvusSearchResult result = toolResults.get(index);
            builder.append(index + 1)
                    .append(". [collection=").append(result.collection()).append(", score=").append(result.score()).append("] ")
                    .append(result.content())
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private String buildAnswerUserPrompt(String message, String toolContext) {
        if (toolContext == null || toolContext.isBlank()) {
            return "User question:\n" + message + "\n\nNo MCP context is available. Answer directly.";
        }
        return "User question:\n" + message + "\n\nMCP context:\n" + toolContext + "\n\nAnswer using the context.";
    }
}
