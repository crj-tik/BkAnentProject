package com.bkanent.agent.milvus.core.model;

import java.util.Map;

/**
 * MilvusSearchResult 数据对象。
 */
public record MilvusSearchResult(
        String collection,
        String documentId,
        String sourceType,
        String sourceId,
        String content,
        Double score,
        Map<String, Object> metadata
) {
}
