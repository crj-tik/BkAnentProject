package com.bkanent.settlement.model;

/**
 * 结算发放状态更新请求。
 */
public record SettlementPayoutUpdateRequest(
        /** 业务属性：payoutStatus。 */
        String payoutStatus,
        /** 业务属性：remark。 */
        String remark
) {
}
