package com.bkanent.settlement.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结算详情响应。
 */
public record SettlementDetailResponse(
        /** 业务属性：id。 */
        Long id,
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
        /** 业务属性：statMonth。 */
        String statMonth,
        /** 业务属性：dealAmount。 */
        BigDecimal dealAmount,
        /** 业务属性：commissionRate。 */
        BigDecimal commissionRate,
        /** 业务属性：commissionAmount。 */
        BigDecimal commissionAmount,
        /** 业务属性：payoutStatus。 */
        String payoutStatus,
        /** 业务属性：payoutTime。 */
        String payoutTime,
        /** 业务属性：ruleCode。 */
        String ruleCode,
        /** 业务属性：remark。 */
        String remark,
        /** 业务属性：splitDetails。 */
        List<SettlementSplitResponse> splitDetails
) {
}
