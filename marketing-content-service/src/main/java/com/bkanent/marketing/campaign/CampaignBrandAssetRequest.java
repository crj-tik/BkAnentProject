package com.bkanent.marketing.campaign;

import java.util.List;

public record CampaignBrandAssetRequest(String assetType, String assetName, String assetUrl,
                                       String platformScope, List<String> tags, String status, String remark) {
    public CampaignBrandAssetRequest { tags = tags == null ? List.of() : List.copyOf(tags); }
}
