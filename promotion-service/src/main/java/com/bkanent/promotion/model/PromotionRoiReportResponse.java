package com.bkanent.promotion.model;

import java.math.BigDecimal;

/**
 * ROI 报表响应。
 */
public record PromotionRoiReportResponse(
        /** 业务属性：platform。 */
        String platform,
        /** 业务属性：publishCount。 */
        Integer publishCount,
        /** 业务属性：totalExposureCount。 */
        Integer totalExposureCount,
        /** 业务属性：totalLeadCount。 */
        Integer totalLeadCount,
        /** 业务属性：totalCostAmount。 */
        BigDecimal totalCostAmount,
        /** 业务属性：averageCtrValue。 */
        BigDecimal averageCtrValue,
        /** 业务属性：averageRoiValue。 */
        BigDecimal averageRoiValue
) {
}
