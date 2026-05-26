package com.bkanent.agent.milvus.core.model;

import java.util.List;

/**
 * MilvusUpsertRequest 数据对象。
 */
public record MilvusUpsertRequest(
        String collectionName,
        List<MilvusVectorDocument> documents
) {
}
