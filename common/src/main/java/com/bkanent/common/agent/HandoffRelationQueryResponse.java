package com.bkanent.common.agent;

import java.time.LocalDateTime;

public record HandoffRelationQueryResponse(
        AgentHandoffPacket packet,
        LocalDateTime createdAt
) {
}
