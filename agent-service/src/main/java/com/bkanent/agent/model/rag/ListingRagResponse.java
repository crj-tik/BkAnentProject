package com.bkanent.agent.model.rag;

import com.bkanent.agent.model.vector.MilvusSearchResult;

import java.util.List;

/**
 * 房源 RAG 查询响应对象。
 */
public record ListingRagResponse(
        /** 原始查询语句。 */
        String query,
        /** 拼接后的上下文内容。 */
        String assembledContext,
        /** 命中的向量检索结果。 */
        List<MilvusSearchResult> matches
) {
}
