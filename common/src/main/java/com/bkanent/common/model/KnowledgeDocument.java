package com.bkanent.common.model;

import java.util.Map;

/**
 * KnowledgeDocument 通用知识文档契约。
 */
public record KnowledgeDocument(
        String documentId,
        String bizType,
        String bizId,
        String title,
        String content,
        Map<String, Object> metadata
) {
}
