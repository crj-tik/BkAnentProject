package com.bkanent.common.model;

import java.math.BigDecimal;

/**
 * ListingDTO 数据传输对象。
 */

public record ListingDTO(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：title。 */
        String title,
        /** 业务属性：address。 */
        String address,
        /** 业务属性：layout。 */
        String layout,
        /** 业务属性：area。 */
        BigDecimal area,
        /** 业务属性：totalPrice。 */
        BigDecimal totalPrice,
        /** 业务属性：status。 */
        String status,
        /** 业务属性：floorLevel。 */
        String floorLevel,
        /** 业务属性：decoration。 */
        String decoration,
        /** 业务属性：schoolZone。 */
        String schoolZone,
        /** 业务属性：traffic。 */
        String traffic,
        /** 业务属性：verificationStatus。 */
        String verificationStatus
) {
}

