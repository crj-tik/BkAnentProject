package com.bkanent.business.model;

/**
 * 房源流通效率响应。
 */
public record ListingTurnoverReportResponse(
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：listingTitle。 */
        String listingTitle,
        /** 业务属性：storeName。 */
        String storeName,
        /** 业务属性：regionName。 */
        String regionName,
        /** 业务属性：statMonth。 */
        String statMonth,
        /** 业务属性：listingToViewingDays。 */
        Integer listingToViewingDays,
        /** 业务属性：viewingToDealDays。 */
        Integer viewingToDealDays,
        /** 业务属性：totalTurnoverDays。 */
        Integer totalTurnoverDays,
        /** 业务属性：turnoverStatus。 */
        String turnoverStatus
) {
}
