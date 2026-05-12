package com.bkanent.promotion.model;

import java.time.LocalDateTime;

/**
 * 宣传发布结果响应。
 */
public record PromotionPublishResultResponse(
        /** 业务属性：publishRecordId。 */
        Long publishRecordId,
        /** 业务属性：contentId。 */
        Long contentId,
        /** 业务属性：platform。 */
        String platform,
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
