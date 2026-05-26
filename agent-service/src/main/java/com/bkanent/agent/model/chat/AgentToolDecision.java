package com.bkanent.agent.model.chat;

/**
 * AgentToolDecision 记录类型。
 */
public record AgentToolDecision(
        boolean usedKnowledgeTool,
        String toolName,
        String toolRequest,
        Integer topK,
        String reason
) {
}
