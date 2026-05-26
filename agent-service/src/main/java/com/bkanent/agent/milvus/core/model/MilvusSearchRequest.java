package com.bkanent.agent.milvus.core.model;

/**
 * MilvusSearchRequest 数据对象。
 */
public record MilvusSearchRequest(
        String collectionName,
        String query,
        int topK,
        Double similarityThreshold,
        String nativeFilterExpression
) {
}
