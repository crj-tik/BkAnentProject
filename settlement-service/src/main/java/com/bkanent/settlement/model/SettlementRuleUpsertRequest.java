package com.bkanent.settlement.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 佣金规则新增请求。
 */
public record SettlementRuleUpsertRequest(
        /** 业务属性：ruleCode。 */
        String ruleCode,
        /** 业务属性：ruleName。 */
        String ruleName,
        /** 业务属性：contractType。 */
        String contractType,
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
        /** 业务属性：status。 */
        String status,
        /** 业务属性：remark。 */
        String remark,
        /** 业务属性：tierRules。 */
        List<SettlementRuleTierRequest> tierRules
) {
}
