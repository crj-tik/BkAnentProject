package com.bkanent.agent.model.distributed;

public record GovernanceRateLimitOverrideRequest(
        String entryType,
        Integer perWindow
) {
}
