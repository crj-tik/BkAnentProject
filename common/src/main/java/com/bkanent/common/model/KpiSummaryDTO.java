package com.bkanent.common.model;

import java.math.BigDecimal;

/**
 * KpiSummaryDTO 数据传输对象。
 */

public record KpiSummaryDTO(
        /** 业务属性：employeeId。 */
        Long employeeId,
        /** 业务属性：employeeName。 */
        String employeeName,
        /** 业务属性：closedDeals。 */
        Integer closedDeals,
        /** 业务属性：newListings。 */
        Integer newListings,
        /** 业务属性：newCustomers。 */
        Integer newCustomers,
        /** 业务属性：completionRate。 */
        BigDecimal completionRate
) {
}

