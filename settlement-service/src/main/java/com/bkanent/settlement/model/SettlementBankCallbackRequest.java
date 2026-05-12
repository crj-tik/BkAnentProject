package com.bkanent.settlement.model;

import java.util.List;

/**
 * 银行回执回调请求。
 */
public record SettlementBankCallbackRequest(
        /** 业务属性：batchNo。 */
        String batchNo,
        /** 业务属性：callbackStatus。 */
        String callbackStatus,
        /** 业务属性：callbackTime。 */
        String callbackTime,
        /** 业务属性：paymentItems。 */
        List<SettlementBankPaymentItemRequest> paymentItems,
        /** 业务属性：remark。 */
        String remark
) {
}
