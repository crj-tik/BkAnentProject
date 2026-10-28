package com.bkanent.common.marketing.campaign;

import java.math.BigDecimal;
import java.time.Instant;

/** Channel observation with provenance and independently evidenced financial metrics. */
public record CampaignEffectResponse(
        String attemptId,
        Long legacyPublishRecordId,
        Long contentId,
        Long listingId,
        String platform,
        Long exposureCount,
        Long clickCount,
        Long privateMessageCount,
        Long leadCount,
        BigDecimal ctr,
        BigDecimal conversionRate,
        BigDecimal leadCost,
        BigDecimal attributableRevenue,
        BigDecimal attributableCost,
        BigDecimal roi,
        String currency,
        String roiDefinitionVersion,
        String sourceSystem,
        String sourceEventId,
        Long sourceVersion,
        Instant windowStart,
        Instant windowEnd,
        Instant observedAt,
        CampaignDataMode dataMode,
        String unavailableReason) {
}
