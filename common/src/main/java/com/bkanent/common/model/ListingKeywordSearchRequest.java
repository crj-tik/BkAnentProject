package com.bkanent.common.model;

import java.math.BigDecimal;

/**
 * ListingKeywordSearchRequest 数据传输对象。
 */
public record ListingKeywordSearchRequest(
        /**
         * 字段：keyword。
         */
        String keyword,
        /**
         * 字段：region。
         */
        String region,
        /**
         * 字段：layout。
         */
        String layout,
        /**
         * 字段：minArea。
         */
        BigDecimal minArea,
        /**
         * 字段：maxArea。
         */
        BigDecimal maxArea,
        /**
         * 字段：minTotalPrice。
         */
        BigDecimal minTotalPrice,
        /**
         * 字段：maxTotalPrice。
         */
        BigDecimal maxTotalPrice,
        /**
         * 字段：topK。
         */
        Integer topK
) {
}
