package com.bkanent.marketing.campaign;

import java.util.List;

public record CampaignBrandAssetResponse(Long id, String assetType, String assetName, String assetUrl,
                                         String platformScope, List<String> tags, String status, String remark,
                                         int revision) { }
