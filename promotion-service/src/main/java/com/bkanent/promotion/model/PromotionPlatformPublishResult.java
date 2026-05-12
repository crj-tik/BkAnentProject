package com.bkanent.promotion.model;

import java.time.LocalDateTime;

/**
 * 平台发布结果。
 */
public record PromotionPlatformPublishResult(
        /** 业务属性：success。 */
        boolean success,
        /** 业务属性：publishStatus。 */
        String publishStatus,
        /** 业务属性：externalPublishId。 */
        String externalPublishId,
        /** 业务属性：publishMessage。 */
        String publishMessage,
        /** 业务属性：publishTime。 */
        LocalDateTime publishTime
) {
}
