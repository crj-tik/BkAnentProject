package com.bkanent.marketing.model;

import java.util.List;

/**
 * 营销内容新增或更新请求。
 */
public record MarketingContentUpsertRequest(
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：platform。 */
        String platform,
        /** 业务属性：title。 */
        String title,
        /** 业务属性：contentType。 */
        String contentType,
        /** 业务属性：copywriting。 */
        String copywriting,
        /** 业务属性：assetUrls。 */
        List<String> assetUrls,
        /** 业务属性：coverImageUrl。 */
        String coverImageUrl,
        /** 业务属性：videoUrl。 */
        String videoUrl,
        /** 业务属性：platformVariant。 */
        String platformVariant,
        /** 业务属性：tags。 */
        List<String> tags,
        /** 业务属性：auditStatus。 */
        String auditStatus,
        /** 业务属性：parentContentId。 */
        Long parentContentId
) {
}
