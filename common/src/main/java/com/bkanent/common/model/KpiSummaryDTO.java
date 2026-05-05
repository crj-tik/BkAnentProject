package com.bkanent.common.model;

import java.math.BigDecimal;

public record KpiSummaryDTO(
        Long employeeId,
        String employeeName,
        Integer closedDeals,
        Integer newListings,
        Integer newCustomers,
        BigDecimal completionRate
) {
}
