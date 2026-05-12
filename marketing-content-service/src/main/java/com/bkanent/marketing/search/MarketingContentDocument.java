package com.bkanent.marketing.search;

import java.util.List;

/**
 * 营销内容检索文档。
 */
public record MarketingContentDocument(
        Long id,
        Long listingId,
        String platform,
        String title,
        String contentType,
        String copywriting,
        List<String> tags,
        String publishStatus,
        String auditStatus,
        String platformVariant
) {
}
