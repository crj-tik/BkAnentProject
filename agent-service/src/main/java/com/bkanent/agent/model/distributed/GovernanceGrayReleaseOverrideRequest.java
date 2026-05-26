package com.bkanent.agent.model.distributed;

public record GovernanceGrayReleaseOverrideRequest(
        Boolean enabled,
        String strategyVersion,
        Boolean preferAsyncA2a
) {
}
