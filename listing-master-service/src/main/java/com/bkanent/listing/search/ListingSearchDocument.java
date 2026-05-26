package com.bkanent.listing.search;

import java.math.BigDecimal;

/**
 * ListingSearchDocument 数据对象。
 */
public record ListingSearchDocument(
        /**
         * 字段：id。
         */
        Long id,
        /**
         * 字段：title。
         */
        String title,
        /**
         * 字段：address。
         */
        String address,
        /**
         * 字段：region。
         */
        String region,
        /**
         * 字段：layout。
         */
        String layout,
        /**
         * 字段：area。
         */
        BigDecimal area,
        /**
         * 字段：totalPrice。
         */
        BigDecimal totalPrice,
        /**
         * 字段：status。
         */
        String status,
        /**
         * 字段：floorLevel。
         */
        String floorLevel,
        /**
         * 字段：decoration。
         */
        String decoration,
        /**
         * 字段：schoolZone。
         */
        String schoolZone,
        /**
         * 字段：traffic。
         */
        String traffic,
        /**
         * 字段：ownerName。
         */
        String ownerName,
        /**
         * 字段：verificationStatus。
         */
        String verificationStatus
) {
}
