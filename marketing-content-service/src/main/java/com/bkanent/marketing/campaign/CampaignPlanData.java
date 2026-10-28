package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.CampaignDataMode;
import java.time.LocalDateTime;

record CampaignPlanData(String planId, long ownerUserId, long contentId, int contentRevision,
                        String contentHash, String assetHash, String targetsHash, String ruleVersion,
                        String snapshotHash, int planVersion, String status, CampaignDataMode dataMode,
                        LocalDateTime createdAt, String contentSnapshotJson, String assetsSnapshotJson) { }
