package com.bkanent.common.model;

import java.util.List;

/**
 * 营销内容传输对象。
 */
public record MarketingContentDTO(
        /** 业务属性：id。 */
        Long id,
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
        /** 业务属性：versionNo。 */
        Integer versionNo,
        /** 业务属性：platformVariant。 */
        String platformVariant,
        /** 业务属性：tags。 */
        List<String> tags,
        /** 业务属性：auditStatus。 */
        String auditStatus,
        /** 业务属性：status。 */
        String status
) {

    public MarketingContentDTO(Long listingId,
                               String platform,
                               String copywriting,
                               List<String> assetUrls,
                               String status) {
        this(null, listingId, platform, null, "TEXT", copywriting, assetUrls, null, null, 1, "DEFAULT", List.of(), "APPROVED", status);
    }
}
