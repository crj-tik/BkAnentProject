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
        BigDecimal budgetMax,
        /** Trusted Auth user owner filter; never accepted from an external request. */
        Long ownerUserId
) {
    public CustomerQueryRequest(String profileType, String keyword, Long brokerId, String intention,
                                BigDecimal budgetMin, BigDecimal budgetMax) {
        this(profileType, keyword, brokerId, intention, budgetMin, budgetMax, null);
    }
}
