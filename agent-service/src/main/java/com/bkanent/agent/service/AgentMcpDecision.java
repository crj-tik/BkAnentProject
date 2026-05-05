package com.bkanent.agent.service;

public record AgentMcpDecision(
        boolean useMcp,
        String toolName,
        String query,
        Integer topK,
        String reason
) {
}
