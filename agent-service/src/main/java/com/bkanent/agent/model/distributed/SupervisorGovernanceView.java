package com.bkanent.agent.model.distributed;

import java.util.Map;

public record SupervisorGovernanceView(
        Map<String, Object> rateLimit,
        Map<String, Object> grayRelease,
        Map<String, Object> eventAudit
) {
}
