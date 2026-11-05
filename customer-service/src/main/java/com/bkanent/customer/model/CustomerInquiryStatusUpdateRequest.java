package com.bkanent.customer.model;

/**
 * 买方房源咨询状态变更请求。
 */
public record CustomerInquiryStatusUpdateRequest(
        /** 业务属性：status（目标业务状态 OPEN/FOLLOWING/CLOSED）。 */
        String status
) {
}
