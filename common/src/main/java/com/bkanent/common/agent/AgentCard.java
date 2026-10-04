package com.bkanent.common.agent;

import java.util.List;
import java.util.Map;

/**
 * AgentCard 用于暴露 Agent 能力摘要。
 */
public record AgentCard(
        String agentId,
        String name,
        String description,
        String version,
        List<String> supportedSkills,
        List<String> supportedDomains,
        Boolean supportsStreaming,
        Boolean supportsAsyncTask,
        String a2aEndpoint,
        List<String> inputModes,
        List<String> outputModes,
        List<AgentSkillDescriptor> skillDescriptors,
        Map<String, Object> capabilities,
        String preferredTransport,
        String protocolVersion
) {
    public AgentCard {
        skillDescriptors = skillDescriptors == null ? List.of() : List.copyOf(skillDescriptors);
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    }

    public AgentCard(String agentId, String name, String description, String version, List<String> supportedSkills,
                     List<String> supportedDomains, Boolean supportsStreaming, Boolean supportsAsyncTask,
                     String a2aEndpoint, List<String> inputModes, List<String> outputModes) {
        this(agentId, name, description, version, supportedSkills, supportedDomains, supportsStreaming,
                supportsAsyncTask, a2aEndpoint, inputModes, outputModes, List.of(), Map.of(), null, null);
    }
}
