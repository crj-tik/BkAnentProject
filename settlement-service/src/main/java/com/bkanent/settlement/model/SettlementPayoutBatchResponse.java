package com.bkanent.settlement.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 发放批次响应。
 */
public record SettlementPayoutBatchResponse(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：batchNo。 */
        String batchNo,
        /** 业务属性：statMonth。 */
        String statMonth,
        /** 业务属性：batchStatus。 */
        String batchStatus,
        /** 业务属性：totalRecords。 */
        Integer totalRecords,
        /** 业务属性：totalAmount。 */
        BigDecimal totalAmount,
        /** 业务属性：submitTime。 */
        String submitTime,
        /** 业务属性：paidTime。 */
        String paidTime,
        /** 业务属性：remark。 */
        String remark,
        /** 业务属性：paymentRecords。 */
        List<SettlementPaymentRecordResponse> paymentRecords
) {
}
