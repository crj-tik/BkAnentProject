package com.bkanent.settlement.model;

import java.math.BigDecimal;

/**
 * 打款流水响应。
 */
public record SettlementPaymentRecordResponse(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：batchId。 */
        Long batchId,
        /** 业务属性：settlementId。 */
        Long settlementId,
        /** 业务属性：payeeEmployeeId。 */
        Long payeeEmployeeId,
        /** 业务属性：payeeName。 */
        String payeeName,
        /** 业务属性：paymentAmount。 */
        BigDecimal paymentAmount,
        /** 业务属性：paymentStatus。 */
        String paymentStatus,
        /** 业务属性：paymentTime。 */
        String paymentTime,
        /** 业务属性：bankSerialNo。 */
        String bankSerialNo,
        /** 业务属性：remark。 */
        String remark
) {
}
