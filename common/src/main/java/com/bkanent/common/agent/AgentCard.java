package com.bkanent.common.agent;

import java.util.List;

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
        List<String> outputModes
) {
}
