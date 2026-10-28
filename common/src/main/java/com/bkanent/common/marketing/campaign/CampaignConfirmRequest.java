package com.bkanent.common.marketing.campaign;

/** Explicit confirmation for one exact prepared snapshot. */
public record CampaignConfirmRequest(String requestId, int expectedPlanVersion, String expectedSnapshotHash) {
}
