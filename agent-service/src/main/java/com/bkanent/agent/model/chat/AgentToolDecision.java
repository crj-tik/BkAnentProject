package com.bkanent.agent.model.chat;

/**
 * Tool decision summary returned by agent chat.
 */
public record AgentToolDecision(
        boolean usedKnowledgeTool,
        String toolName,
        String toolRequest,
        Integer topK,
        String reason
) {
}
