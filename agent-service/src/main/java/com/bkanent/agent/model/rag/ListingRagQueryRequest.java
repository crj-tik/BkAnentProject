package com.bkanent.agent.model.rag;

/**
 * 房源 RAG 查询请求对象。
 */
public record ListingRagQueryRequest(
        /** 业务属性：query。 */
        String query,
        /** 业务属性：topK。 */
        Integer topK,
        /** 业务属性：collectionName。 */
        String collectionName
) {
}
