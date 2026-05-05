package com.bkanent.agent.service;

import com.bkanent.agent.config.AgentQwenProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentMcpDecisionService {

    private static final String DECISION_SYSTEM_PROMPT = """
            You are the MCP decision maker for a real-estate agent service.
            Your job is to decide whether external knowledge retrieval is needed.
            The only available tool is a Milvus vector search tool named milvus_search.
            Use MCP when the question needs listing knowledge, business rules, historical knowledge,
            internal knowledge, KPI benchmarks, or contract and settlement details.
            Do not use MCP for casual chat, pure rewriting, or answers that rely only on the user's own text.
            Return JSON only. No markdown. No explanation outside JSON.
            JSON schema:
            {
              "useMcp": true,
              "toolName": "milvus_search",
              "query": "short retrieval query",
              "topK": 4,
              "reason": "short reason"
            }
            """;

    private final QwenChatService qwenChatService;
    private final AgentQwenProperties agentQwenProperties;
    private final ObjectMapper objectMapper;

    public AgentMcpDecisionService(QwenChatService qwenChatService,
                                   AgentQwenProperties agentQwenProperties,
                                   ObjectMapper objectMapper) {
        this.qwenChatService = qwenChatService;
        this.agentQwenProperties = agentQwenProperties;
        this.objectMapper = objectMapper;
    }

    public AgentMcpDecision decide(String userMessage, boolean allowMcp) {
        if (!allowMcp) {
            return new AgentMcpDecision(false, "", "", agentQwenProperties.getDefaultTopK(), "mcp disabled by request");
        }

        String raw = qwenChatService.completeForDecision(DECISION_SYSTEM_PROMPT, userMessage);
        try {
            AgentMcpDecision decision = objectMapper.readValue(extractJson(raw), AgentMcpDecision.class);
            Integer topK = normalizeTopK(decision.topK());
            String toolName = decision.useMcp() ? "milvus_search" : "";
            String query = decision.query() == null || decision.query().isBlank() ? userMessage : decision.query();
            return new AgentMcpDecision(decision.useMcp(), toolName, query, topK, defaultReason(decision.reason()));
        }
        catch (Exception ex) {
            return new AgentMcpDecision(true, "milvus_search", userMessage, agentQwenProperties.getDefaultTopK(),
                    "decision parse fallback: " + ex.getMessage());
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("empty model response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("json block not found");
        }
        return raw.substring(start, end + 1);
    }

    private Integer normalizeTopK(Integer topK) {
        if (topK == null) {
            return agentQwenProperties.getDefaultTopK();
        }
        return Math.max(1, Math.min(8, topK));
    }

    private String defaultReason(String reason) {
        return reason == null || reason.isBlank() ? "model decision" : reason;
    }
}
