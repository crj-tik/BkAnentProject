package com.bkanent.marketing.campaign;

import java.math.BigDecimal;
import java.time.Instant;

record CampaignEffectObservation(String sourceSystem, String sourceEventId, long sourceVersion,
                                 Instant windowStart, Instant windowEnd, Instant observedAt,
                                 Long exposureCount, Long clickCount, Long privateMessageCount,
                                 Long leadCount, BigDecimal attributableRevenue, BigDecimal attributableCost,
                                 String currency, String roiDefinitionVersion, String unavailableReason) { }
