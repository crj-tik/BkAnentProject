package com.bkanent.common.model;

import java.math.BigDecimal;

/**
 * 客户与业主档案传输对象。
 */
public record CustomerProfileDTO(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：profileType。 */
        String profileType,
        /** 业务属性：name。 */
        String name,
        /** 业务属性：mobile。 */
        String mobile,
        /** 业务属性：wechatNo。 */
        String wechatNo,
        /** 业务属性：gender。 */
        String gender,
        /** 业务属性：intention。 */
        String intention,
        /** 业务属性：preferredArea。 */
        String preferredArea,
        /** 业务属性：preferredLayout。 */
        String preferredLayout,
        /** 业务属性：budgetMin。 */
        BigDecimal budgetMin,
        /** 业务属性：budgetMax。 */
        BigDecimal budgetMax,
        /** 业务属性：preferredAreaMin。 */
        BigDecimal preferredAreaMin,
        /** 业务属性：preferredAreaMax。 */
        BigDecimal preferredAreaMax,
        /** 业务属性：brokerId。 */
        Long brokerId,
        /** 业务属性：sourceChannel。 */
        String sourceChannel,
        /** 业务属性：remark。 */
        String remark
) {
}
