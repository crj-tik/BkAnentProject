package com.bkanent.common.marketing.campaign;

import java.util.Map;

/** Immutable target binding included in a prepared plan. */
public record CampaignTargetSnapshot(
        int targetIndex,
        String platform,
        Long accountId,
        String accountRevision,
        String externalAccountRef,
        String argumentsHash,
        Map<String, String> arguments) {
}
