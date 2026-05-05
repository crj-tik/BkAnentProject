package com.bkanent.agent.service;

import com.bkanent.agent.mcp.MilvusSearchResult;

import java.util.List;

public record AgentChatResponse(
        String answer,
        String model,
        AgentMcpDecision decision,
        List<MilvusSearchResult> toolResults,
        String toolContext
) {
}
