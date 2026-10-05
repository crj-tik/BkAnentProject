package com.bkanent.agent.registry;

import com.bkanent.common.agent.AgentCard;

import java.util.Map;

/**
 * RegisteredAgentDescriptor 注册 Agent 描述。
 *
 * @param metadata 注册来源附带的原始实例元数据，供协议扩展使用，
 *                 静态注册时由配置转换而来；不会为 null
 */
public record RegisteredAgentDescriptor(
        String agentId,
        String baseUrl,
        String agentCardPath,
        String a2aPath,
        AgentRuntimeType runtimeType,
        AgentDescriptorSource source,
        AgentCard agentCard,
        Map<String, String> metadata
) {
    public RegisteredAgentDescriptor {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
