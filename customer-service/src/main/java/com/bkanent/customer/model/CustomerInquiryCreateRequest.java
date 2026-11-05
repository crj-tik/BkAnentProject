package com.bkanent.customer.model;

/**
 * 买方房源咨询登记请求；requestId 为幂等键（actor + 操作 + requestId 去重）。
 */
public record CustomerInquiryCreateRequest(
        /** 业务属性：listingId（咨询目标房源，业务目标而非身份）。 */
        Long listingId,
        /** 业务属性：inquiryChannel（咨询渠道）。 */
        String inquiryChannel,
        /** 业务属性：inquiryContent（咨询内容）。 */
        String inquiryContent,
        /** 业务属性：requestId（幂等键）。 */
        String requestId
) {
}
