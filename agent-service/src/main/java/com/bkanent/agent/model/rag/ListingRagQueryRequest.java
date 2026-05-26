package com.bkanent.agent.model.rag;

import java.math.BigDecimal;

/**
 * ListingRagQueryRequest 数据对象。
 */
public record ListingRagQueryRequest(
        String userId,
        String query,
        Integer topK,
        Integer keywordTopK,
        Integer vectorTopK,
        String collectionName,
        String region,
        BigDecimal minTotalPrice,
        BigDecimal maxTotalPrice,
        String layout,
        BigDecimal minArea,
        BigDecimal maxArea
) {
}
