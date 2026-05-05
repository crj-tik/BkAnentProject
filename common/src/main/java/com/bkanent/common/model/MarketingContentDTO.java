package com.bkanent.common.model;

import java.util.List;

public record MarketingContentDTO(
        Long listingId,
        String platform,
        String copywriting,
        List<String> assetUrls,
        String status
) {
}
