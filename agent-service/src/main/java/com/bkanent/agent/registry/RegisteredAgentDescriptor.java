package com.bkanent.agent.registry;

import com.bkanent.common.agent.AgentCard;

/**
 * RegisteredAgentDescriptor 注册 Agent 描述。
 */
public record RegisteredAgentDescriptor(
        String agentId,
        String baseUrl,
        String agentCardPath,
        String a2aPath,
        String a2aTaskCreatePath,
        String a2aTaskStatusPath,
        String a2aTaskStreamPath,
        AgentRuntimeType runtimeType,
        String officialPayloadMode,
        AgentDescriptorSource source,
        AgentCard agentCard
) {
}
