package com.bkanent.agent.model.chat;

/**
 * AgentChatRequest 数据对象。
 */
public record AgentChatRequest(
        String userId,
        String message,
        String collectionName,
        Integer topK,
        Boolean allowMcp
) {
    /**
     * 处理AgentChatRequest。
     */
    public AgentChatRequest(String message, String collectionName, Integer topK) {
        this(null, message, collectionName, topK, true);
    }
}
