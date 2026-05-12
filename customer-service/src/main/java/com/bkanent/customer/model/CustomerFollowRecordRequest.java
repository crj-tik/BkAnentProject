package com.bkanent.customer.model;

import java.time.LocalDateTime;

/**
 * 跟进记录新增请求。
 */
public record CustomerFollowRecordRequest(
        /** 业务属性：brokerId。 */
        Long brokerId,
        /** 业务属性：followType。 */
        String followType,
        /** 业务属性：content。 */
        String content,
        /** 业务属性：resultTag。 */
        String resultTag,
        /** 业务属性：nextFollowTime。 */
        LocalDateTime nextFollowTime
) {
}
