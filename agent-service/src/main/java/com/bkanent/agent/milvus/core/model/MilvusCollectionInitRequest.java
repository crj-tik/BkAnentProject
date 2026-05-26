package com.bkanent.agent.milvus.core.model;

/**
 * MilvusCollectionInitRequest 数据对象。
 */
public record MilvusCollectionInitRequest(
        String collectionName,
        Integer dimension
) {
}
