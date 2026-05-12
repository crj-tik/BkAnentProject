package com.bkanent.business.model;

import java.math.BigDecimal;

/**
 * KPI 考核响应。
 */
public record KpiAssessmentResponse(
        /** 业务属性：employeeId。 */
        Long employeeId,
        /** 业务属性：employeeName。 */
        String employeeName,
        /** 业务属性：storeName。 */
        String storeName,
        /** 业务属性：regionName。 */
        String regionName,
        /** 业务属性：saleDeals。 */
        Integer saleDeals,
        /** 业务属性：rentalDeals。 */
        Integer rentalDeals,
        /** 业务属性：closedDeals。 */
        Integer closedDeals,
        /** 业务属性：performanceAmount。 */
        BigDecimal performanceAmount,
        /** 业务属性：completionRate。 */
        BigDecimal completionRate,
        /** 业务属性：conversionRate。 */
        BigDecimal conversionRate,
        /** 业务属性：satisfactionScore。 */
        BigDecimal satisfactionScore
) {
}
