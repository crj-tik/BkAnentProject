package com.bkanent.common.marketing.campaign;

import java.util.List;

/** Request to snapshot approved content, selected materials, account grants, and targets. */
public record CampaignPlanPrepareRequest(
        Long contentId,
        List<Long> brandAssetIds,
        List<CampaignTargetRequest> targets,
        String requestId) {
    public CampaignPlanPrepareRequest {
        brandAssetIds = brandAssetIds == null ? List.of() : List.copyOf(brandAssetIds);
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
