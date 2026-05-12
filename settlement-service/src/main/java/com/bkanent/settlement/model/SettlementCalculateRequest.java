package com.bkanent.settlement.model;

import java.math.BigDecimal;

/**
 * 结算计算请求。
 */
public record SettlementCalculateRequest(
        /** 业务属性：employeeId。 */
        Long employeeId,
        /** 业务属性：employeeName。 */
        String employeeName,
        /** 业务属性：teamName。 */
        String teamName,
        /** 业务属性：storeName。 */
        String storeName,
        /** 业务属性：contractId。 */
        Long contractId,
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：contractType。 */
        String contractType,
        /** 业务属性：statMonth。 */
        String statMonth,
        /** 业务属性：dealAmount。 */
        BigDecimal dealAmount,
        /** 业务属性：commissionRate。 */
        BigDecimal commissionRate,
        /** 业务属性：storeSplitRatio。 */
        BigDecimal storeSplitRatio,
        /** 业务属性：teamSplitRatio。 */
        BigDecimal teamSplitRatio,
        /** 业务属性：ruleCode。 */
        String ruleCode,
        /** 业务属性：remark。 */
        String remark
) {
}
