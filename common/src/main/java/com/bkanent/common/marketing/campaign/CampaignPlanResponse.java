package com.bkanent.common.marketing.campaign;

import java.time.LocalDateTime;
import java.util.List;

/** Caller-visible immutable snapshot and current plan state. */
public record CampaignPlanResponse(
        String planId,
        Long contentId,
        int contentRevision,
        String contentHash,
        String assetHash,
        String targetsHash,
        String ruleVersion,
        String snapshotHash,
        int planVersion,
        String status,
        CampaignDataMode dataMode,
        List<CampaignTargetSnapshot> targets,
        LocalDateTime createdAt,
        LocalDateTime expiresAt) {
    public CampaignPlanResponse {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
