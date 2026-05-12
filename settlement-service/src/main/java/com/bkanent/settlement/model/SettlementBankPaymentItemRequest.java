package com.bkanent.settlement.model;

/**
 * 银行回执明细请求。
 */
public record SettlementBankPaymentItemRequest(
        /** 业务属性：settlementId。 */
        Long settlementId,
        /** 业务属性：paymentStatus。 */
        String paymentStatus,
        /** 业务属性：bankSerialNo。 */
        String bankSerialNo,
        /** 业务属性：paymentTime。 */
        String paymentTime,
        /** 业务属性：remark。 */
        String remark
) {
}
