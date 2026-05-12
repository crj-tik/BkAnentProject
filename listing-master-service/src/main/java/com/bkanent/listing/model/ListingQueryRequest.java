package com.bkanent.listing.model;

import java.math.BigDecimal;

/**
 * ListingQueryRequest 房源检索请求对象。
 */
public record ListingQueryRequest(
        /** 业务属性：keyword。 */
        String keyword,
        /** 业务属性：status。 */
        String status,
        /** 业务属性：brokerId。 */
        Long brokerId,
        /** 业务属性：minArea。 */
        BigDecimal minArea,
        /** 业务属性：maxArea。 */
        BigDecimal maxArea,
        /** 业务属性：minTotalPrice。 */
        BigDecimal minTotalPrice,
        /** 业务属性：maxTotalPrice。 */
        BigDecimal maxTotalPrice,
        /** 业务属性：verifiedOnly。 */
        Boolean verifiedOnly
) {
}
