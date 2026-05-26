package com.bkanent.common.model;

/**
 * ListingKeywordSearchResultDTO 数据传输对象。
 */
public record ListingKeywordSearchResultDTO(
        /**
         * 字段：listing。
         */
        ListingDTO listing,
        /**
         * 字段：score。
         */
        Double score
) {
}
