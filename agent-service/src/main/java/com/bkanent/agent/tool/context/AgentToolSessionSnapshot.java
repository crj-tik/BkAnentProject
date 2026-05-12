package com.bkanent.agent.tool.context;

import com.bkanent.agent.model.vector.MilvusSearchResult;

import java.util.List;

/**
 * Agent 工具调用快照。
 */
public record AgentToolSessionSnapshot(
        boolean usedTool,
        String firstToolName,
        String firstToolQuery,
        Integer topK,
        List<MilvusSearchResult> milvusResults,
        String toolContext
) {
}
