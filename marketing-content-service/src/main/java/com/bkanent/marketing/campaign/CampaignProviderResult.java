package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.CampaignDataMode;

record CampaignProviderResult(String status, String externalPublishId, String message, CampaignDataMode dataMode) { }
