package com.bkanent.agent.model.vector;

import java.util.List;

/**
 * Milvus 向量写入请求对象。
 */
public record MilvusUpsertRequest(
        /** 集合名称。 */
        String collectionName,
        /** 待写入向量文档。 */
        List<MilvusVectorDocument> documents
) {
}
