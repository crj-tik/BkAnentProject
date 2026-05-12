package com.bkanent.common.model;

import java.math.BigDecimal;

/**
 * 结算概要传输对象。
 */
public record SettlementSummaryDTO(
        /** 业务属性：employeeId。 */
        Long employeeId,
        /** 业务属性：month。 */
        String month,
        /** 业务属性：commissionAmount。 */
        BigDecimal commissionAmount,
        /** 业务属性：payoutStatus。 */
        String payoutStatus
) {
}
