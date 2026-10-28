package com.bkanent.common.marketing.campaign;

import java.math.BigDecimal;

/** Aggregated campaign metrics without mixing provenance modes or inventing revenue. */
public record CampaignRoiReportResponse(
        String platform,
        CampaignDataMode dataMode,
        long publishCount,
        Long totalExposure,
        Long totalLeads,
        BigDecimal totalAttributableRevenue,
        BigDecimal totalAttributableCost,
        BigDecimal leadCost,
        BigDecimal roi,
        long unknownObservationCount,
        String currency,
        String roiDefinitionVersion) {
}
