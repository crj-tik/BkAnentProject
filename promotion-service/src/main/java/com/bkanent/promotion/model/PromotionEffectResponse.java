package com.bkanent.promotion.model;

import java.math.BigDecimal;

/**
 * 宣传效果响应。
 */
public record PromotionEffectResponse(
        /** 业务属性：publishRecordId。 */
        Long publishRecordId,
        /** 业务属性：contentId。 */
        Long contentId,
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：platform。 */
        String platform,
        /** 业务属性：exposureCount。 */
        Integer exposureCount,
        /** 业务属性：clickCount。 */
        Integer clickCount,
        /** 业务属性：privateMessageCount。 */
        Integer privateMessageCount,
        /** 业务属性：leadCount。 */
        Integer leadCount,
        /** 业务属性：ctrValue。 */
        BigDecimal ctrValue,
        /** 业务属性：conversionRate。 */
        BigDecimal conversionRate,
        /** 业务属性：roiValue。 */
        BigDecimal roiValue,
        /** 业务属性：statDate。 */
        String statDate
) {
}
