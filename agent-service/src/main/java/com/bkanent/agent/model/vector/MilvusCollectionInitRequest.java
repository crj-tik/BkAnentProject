package com.bkanent.agent.model.vector;

/**
 * Milvus 集合初始化请求对象。
 */
public record MilvusCollectionInitRequest(
        /** 集合名称。 */
        String collectionName,
        /** 向量维度。 */
        Integer dimension
) {
}
