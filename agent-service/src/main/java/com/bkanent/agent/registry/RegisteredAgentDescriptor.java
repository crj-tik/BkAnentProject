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
        AgentRuntimeType runtimeType,
        AgentDescriptorSource source,
        AgentCard agentCard
) {
}
