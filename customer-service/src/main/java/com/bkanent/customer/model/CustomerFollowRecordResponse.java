package com.bkanent.customer.model;

import java.time.LocalDateTime;

/**
 * 跟进记录响应。
 */
public record CustomerFollowRecordResponse(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：customerId。 */
        Long customerId,
        /** 业务属性：brokerId。 */
        Long brokerId,
        /** 业务属性：followType。 */
        String followType,
        /** 业务属性：content。 */
        String content,
        /** 业务属性：resultTag。 */
        String resultTag,
        /** 业务属性：nextFollowTime。 */
        LocalDateTime nextFollowTime,
        /** 业务属性：createdAt。 */
        LocalDateTime createdAt
) {
}
