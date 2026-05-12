package com.bkanent.settlement.model;

import java.math.BigDecimal;

/**
 * 分佣明细响应。
 */
public record SettlementSplitResponse(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：settlementId。 */
        Long settlementId,
        /** 业务属性：splitScope。 */
        String splitScope,
        /** 业务属性：splitTargetName。 */
        String splitTargetName,
        /** 业务属性：splitRatio。 */
        BigDecimal splitRatio,
        /** 业务属性：splitAmount。 */
        BigDecimal splitAmount,
        /** 业务属性：remark。 */
        String remark
) {
}
