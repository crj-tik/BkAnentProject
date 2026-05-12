package com.bkanent.customer.model;

import java.math.BigDecimal;

/**
 * 客户档案查询请求。
 */
public record CustomerQueryRequest(
        /** 业务属性：profileType。 */
        String profileType,
        /** 业务属性：keyword。 */
        String keyword,
        /** 业务属性：brokerId。 */
        Long brokerId,
        /** 业务属性：intention。 */
        String intention,
        /** 业务属性：budgetMin。 */
        BigDecimal budgetMin,
        /** 业务属性：budgetMax。 */
        BigDecimal budgetMax
) {
}
