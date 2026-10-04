package com.bkanent.agent.model.chat;

/**
 * AgentChatRequest 数据对象。
 */
public record AgentChatRequest(
        String userId,
        String message,
        String collectionName,
        Integer topK,
        Boolean allowMcp,
        com.bkanent.common.agent.SkillSelection skill,
        String continueRunId,
        String sessionId,
        String requestId
) {
    public AgentChatRequest(String userId, String message, String collectionName, Integer topK, Boolean allowMcp) {
        this(userId, message, collectionName, topK, allowMcp, null, null, null, null);
    }
    /**
     * 处理AgentChatRequest。
     */
    public AgentChatRequest(String message, String collectionName, Integer topK) {
        this(null, message, collectionName, topK, true);
    }
}
