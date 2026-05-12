package com.bkanent.business.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 聚合大数据看板响应。
 */
public record BigDataDashboardResponse(
        /** 业务属性：month。 */
        String month,
        /** 业务属性：startDate。 */
        String startDate,
        /** 业务属性：endDate。 */
        String endDate,
        /** 业务属性：regionName。 */
        String regionName,
        /** 业务属性：totalStoreCount。 */
        Integer totalStoreCount,
        /** 业务属性：totalActiveListingCount。 */
        Integer totalActiveListingCount,
        /** 业务属性：totalViewingCount。 */
        Integer totalViewingCount,
        /** 业务属性：totalNewCustomerCount。 */
        Integer totalNewCustomerCount,
        /** 业务属性：totalDealCount。 */
        Integer totalDealCount,
        /** 业务属性：totalPerformanceAmount。 */
        BigDecimal totalPerformanceAmount,
        /** 业务属性：averageSatisfactionScore。 */
        BigDecimal averageSatisfactionScore,
        /** 业务属性：averageTurnoverDays。 */
        BigDecimal averageTurnoverDays,
        /** 业务属性：storeAggregates。 */
        List<DashboardStoreAggregateResponse> storeAggregates,
        /** 业务属性：regionAggregates。 */
        List<DashboardRegionAggregateResponse> regionAggregates,
        /** 业务属性：topBrokers。 */
        List<RankingItemResponse> topBrokers,
        /** 业务属性：turnoverHighlights。 */
        List<ListingTurnoverReportResponse> turnoverHighlights
) {
}
