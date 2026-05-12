package com.bkanent.marketing.model;

/**
 * 营销内容检索请求。
 */
public record MarketingContentSearchRequest(
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：platform。 */
        String platform,
        /** 业务属性：keyword。 */
        String keyword,
        /** 业务属性：tag。 */
        String tag,
        /** 业务属性：contentType。 */
        String contentType,
        /** 业务属性：auditStatus。 */
        String auditStatus,
        /** 业务属性：publishStatus。 */
        String publishStatus
) {
}
