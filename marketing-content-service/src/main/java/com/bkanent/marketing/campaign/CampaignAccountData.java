package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.CampaignDataMode;

record CampaignAccountData(long id, String platform, String externalAccountRef, String permissionCode,
                           int revision, CampaignDataMode dataMode, boolean active) { }
