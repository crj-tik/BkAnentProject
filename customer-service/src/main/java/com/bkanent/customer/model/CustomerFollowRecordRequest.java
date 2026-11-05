package com.bkanent.customer.model;

import java.time.LocalDateTime;

/**
 * 跟进记录新增请求；inquiryId 为可选关联咨询（历史请求可不传，不推断）。
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
        LocalDateTime nextFollowTime,
        /** 业务属性：inquiryId（可选，须属于同一客户）。 */
        Long inquiryId
) {
}
