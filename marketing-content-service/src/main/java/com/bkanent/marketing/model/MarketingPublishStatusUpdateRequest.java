package com.bkanent.marketing.model;

import java.time.LocalDateTime;

/**
 * 营销内容发布状态更新请求。
 */
public record MarketingPublishStatusUpdateRequest(
        /** 业务属性：publishStatus。 */
        String publishStatus,
        /** 业务属性：publishMessage。 */
        String publishMessage,
        /** 业务属性：externalPublishId。 */
        String externalPublishId,
        /** 业务属性：publishTime。 */
        LocalDateTime publishTime
) {
}
