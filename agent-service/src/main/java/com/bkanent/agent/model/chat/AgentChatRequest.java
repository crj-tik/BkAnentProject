package com.bkanent.agent.model.chat;

import com.bkanent.agent.enums.AgentExecutionMode;

/**
 * Agent chat request.
 */
public record AgentChatRequest(
        String message,
        String collectionName,
        Integer topK,
        Boolean allowMcp,
        AgentExecutionMode executionMode
) {
    public AgentChatRequest(String message, String collectionName, Integer topK) {
        this(message, collectionName, topK, true, AgentExecutionMode.TOOL);
    }
}
