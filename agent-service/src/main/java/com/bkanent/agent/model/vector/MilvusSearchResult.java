package com.bkanent.agent.model.vector;

/**
 * Milvus 检索结果。
 */
public record MilvusSearchResult(
        /** 集合名称。 */
        String collection,
        /** 匹配内容。 */
        String content,
        /** 相似度得分。 */
        Double score
) {
}
