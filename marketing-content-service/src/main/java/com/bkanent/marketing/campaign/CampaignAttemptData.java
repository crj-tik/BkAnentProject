package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.CampaignDataMode;

import java.time.LocalDateTime;

record CampaignAttemptData(String attemptId, String receiptId, String planId, int targetIndex,
                           String platform, long accountId, String status, CampaignDataMode dataMode,
                           String externalPublishId, String message, long attemptVersion,
                           LocalDateTime updatedAt) { }
