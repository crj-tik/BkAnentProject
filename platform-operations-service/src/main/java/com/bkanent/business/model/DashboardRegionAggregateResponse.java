package com.bkanent.business.model;

import java.math.BigDecimal;

/**
 * 大数据看板区域聚合响应。
 */
public record DashboardRegionAggregateResponse(
        /** 业务属性：regionName。 */
        String regionName,
        /** 业务属性：storeCount。 */
        Integer storeCount,
        /** 业务属性：activeListingCount。 */
        Integer activeListingCount,
        /** 业务属性：viewingCount。 */
        Integer viewingCount,
        /** 业务属性：newCustomerCount。 */
        Integer newCustomerCount,
        /** 业务属性：dealCount。 */
        Integer dealCount,
        /** 业务属性：performanceAmount。 */
        BigDecimal performanceAmount,
        /** 业务属性：satisfactionScore。 */
        BigDecimal satisfactionScore
) {
}
