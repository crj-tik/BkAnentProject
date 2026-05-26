package com.bkanent.agent.model.rag;

import java.math.BigDecimal;
import java.util.List;

/**
 * ListingRagMatch 数据对象。
 */
public record ListingRagMatch(
        Long listingId,
        String title,
        String address,
        String layout,
        BigDecimal area,
        BigDecimal totalPrice,
        List<String> recallSources,
        Double keywordScore,
        Double vectorScore,
        Double rerankScore,
        String content
) {
}
