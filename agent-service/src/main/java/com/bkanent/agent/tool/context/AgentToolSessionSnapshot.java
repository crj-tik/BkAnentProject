package com.bkanent.agent.tool.context;

import com.bkanent.agent.milvus.core.model.MilvusSearchResult;

import java.util.List;

/**
 * AgentToolSessionSnapshot 数据对象。
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
