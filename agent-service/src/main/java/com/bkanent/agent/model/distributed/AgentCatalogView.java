package com.bkanent.agent.model.distributed;

import java.util.List;

public record AgentCatalogView(
        String agentId,
        String name,
        String description,
        String baseUrl,
        String a2aEndpoint,
        String runtimeType,
        String officialPayloadMode,
        String source,
        List<String> supportedDomains,
        List<String> supportedSkills,
        Boolean supportsStreaming,
        Boolean supportsAsyncTask
) {
}
