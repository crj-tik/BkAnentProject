package com.bkanent.promotion.model;

/**
 * 宣传效果同步请求。
 */
public record PromotionEffectSyncRequest(
        /** 业务属性：publishRecordId。 */
        Long publishRecordId,
        /** 业务属性：statDate。 */
        String statDate
) {
}
