package com.bkanent.promotion.model;

import java.math.BigDecimal;

/**
 * 宣传发布请求。
 */
public record PromotionPublishRequest(
        /** 业务属性：contentId。 */
        Long contentId,
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：platform。 */
        String platform,
        /** 业务属性：channelAccount。 */
        String channelAccount,
        /** 业务属性：operatorName。 */
        String operatorName,
        /** 业务属性：costAmount。 */
        BigDecimal costAmount
) {
}
