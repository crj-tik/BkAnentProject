package com.bkanent.agent.service;

public record AgentChatRequest(
        String message,
        String collectionName,
        Integer topK,
        Boolean allowMcp
) {
}
