package com.bkanent.settlement.model;

import java.math.BigDecimal;

/**
 * 月度提成汇总响应。
 */
public record SettlementMonthlySummaryResponse(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：summaryScope。 */
        String summaryScope,
        /** 业务属性：employeeId。 */
        Long employeeId,
        /** 业务属性：employeeName。 */
        String employeeName,
        /** 业务属性：teamName。 */
        String teamName,
        /** 业务属性：statMonth。 */
        String statMonth,
        /** 业务属性：dealCount。 */
        Integer dealCount,
        /** 业务属性：totalDealAmount。 */
        BigDecimal totalDealAmount,
        /** 业务属性：totalCommissionAmount。 */
        BigDecimal totalCommissionAmount,
        /** 业务属性：payoutStatus。 */
        String payoutStatus,
        /** 业务属性：remark。 */
        String remark
) {
}
