package com.bkanent.common.marketing.campaign;

import java.time.LocalDateTime;

/** Durable result for one platform/account target. */
public record CampaignAttemptReceipt(
        String attemptId,
        int targetIndex,
        String platform,
        Long accountId,
        String status,
        String externalPublishId,
        String message,
        CampaignDataMode dataMode,
        LocalDateTime updatedAt) {
}
