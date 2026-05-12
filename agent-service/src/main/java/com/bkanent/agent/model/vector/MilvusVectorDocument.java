package com.bkanent.agent.model.vector;

import java.util.List;

/**
 * Milvus 向量文档对象。
 */
public record MilvusVectorDocument(
        /** 文档主键。 */
        String documentId,
        /** 来源类型。 */
        String sourceType,
        /** 来源编号。 */
        String sourceId,
        /** 文本内容。 */
        String content,
        /** 向量数据。 */
        List<Float> vector
) {
}
