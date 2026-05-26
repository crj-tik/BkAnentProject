package com.bkanent.agent.milvus.core.model;

import java.util.List;
import java.util.Map;

/**
 * MilvusVectorDocument 记录类型。
 */
public record MilvusVectorDocument(
        String documentId,
        String sourceType,
        String sourceId,
        String content,
        List<Float> vector,
        Map<String, Object> metadata
) {
}
