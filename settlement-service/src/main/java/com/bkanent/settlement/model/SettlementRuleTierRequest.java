package com.bkanent.settlement.model;

import java.math.BigDecimal;

/**
 * 阶梯佣金规则请求。
 */
public record SettlementRuleTierRequest(
        /** 业务属性：tierLevel。 */
        Integer tierLevel,
        /** 业务属性：minDealAmount。 */
        BigDecimal minDealAmount,
        /** 业务属性：maxDealAmount。 */
        BigDecimal maxDealAmount,
        /** 业务属性：commissionRate。 */
        BigDecimal commissionRate,
        /** 业务属性：storeSplitRatio。 */
        BigDecimal storeSplitRatio,
        /** 业务属性：teamSplitRatio。 */
        BigDecimal teamSplitRatio,
        /** 业务属性：remark。 */
        String remark
) {
}
