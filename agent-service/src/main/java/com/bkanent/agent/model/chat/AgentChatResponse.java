package com.bkanent.agent.model.chat;

import com.bkanent.agent.milvus.core.model.MilvusSearchResult;

import java.util.List;

/**
 * AgentChatResponse 数据对象。
 */
public record AgentChatResponse(
        String answer,
        String model,
        AgentToolDecision decision,
        List<MilvusSearchResult> toolResults,
        String toolContext
) {
}
