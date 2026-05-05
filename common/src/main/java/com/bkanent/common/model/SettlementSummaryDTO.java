package com.bkanent.common.model;

import java.math.BigDecimal;

public record SettlementSummaryDTO(
        Long employeeId,
        String month,
        BigDecimal commissionAmount,
        String payoutStatus
) {
}
