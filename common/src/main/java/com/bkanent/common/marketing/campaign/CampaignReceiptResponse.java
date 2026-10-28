package com.bkanent.common.marketing.campaign;

import java.time.LocalDateTime;
import java.util.List;

/** Idempotently queryable receipt for one confirmation request. */
public record CampaignReceiptResponse(
        String receiptId,
        String planId,
        String requestId,
        String status,
        CampaignDataMode dataMode,
        LocalDateTime createdAt,
        List<CampaignAttemptReceipt> attempts) {
    public CampaignReceiptResponse {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }
}
