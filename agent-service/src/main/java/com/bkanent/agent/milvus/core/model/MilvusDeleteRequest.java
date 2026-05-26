package com.bkanent.agent.milvus.core.model;

import java.util.List;

/**
 * MilvusDeleteRequest 数据对象。
 */
public record MilvusDeleteRequest(
        String collectionName,
        List<String> documentIds
) {
}
