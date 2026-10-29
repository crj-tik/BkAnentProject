package com.bkanent.knowledge.api;

import java.util.List;

public record GenerateKnowledgeRequest(String requestId, Long expectedVersion,
                                      List<String> sourceAssetIds, String rulesVersion) {
    public GenerateKnowledgeRequest {
        sourceAssetIds = sourceAssetIds == null ? List.of() : List.copyOf(sourceAssetIds);
    }
}
