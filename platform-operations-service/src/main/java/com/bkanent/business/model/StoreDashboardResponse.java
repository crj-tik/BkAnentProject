package com.bkanent.business.model;

import java.math.BigDecimal;

/**
 * 门店经营仪表盘响应。
 */
public record StoreDashboardResponse(
        /** 业务属性：storeName。 */
        String storeName,
        /** 业务属性：regionName。 */
        String regionName,
        /** 业务属性：statDate。 */
        String statDate,
        /** 业务属性：activeListingCount。 */
        Integer activeListingCount,
        /** 业务属性：todayViewingCount。 */
        Integer todayViewingCount,
        /** 业务属性：todayNewCustomerCount。 */
        Integer todayNewCustomerCount,
        /** 业务属性：todayDealCount。 */
        Integer todayDealCount,
        /** 业务属性：todayPerformanceAmount。 */
        BigDecimal todayPerformanceAmount,
        /** 业务属性：satisfactionScore。 */
        BigDecimal satisfactionScore
) {
}
