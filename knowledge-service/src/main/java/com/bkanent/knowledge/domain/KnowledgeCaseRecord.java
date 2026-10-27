package com.bkanent.knowledge.domain;

import java.time.OffsetDateTime;
import java.util.List;

public record KnowledgeCaseRecord(String knowledgeId, String scopeType, String ownerActorId, Long storeId,
                                  String title, String businessProblem, String scene, List<String> tags,
                                  KnowledgeState state, int currentRevision, Integer publishedRevision,
                                  String dataMode, long version, OffsetDateTime createdAt,
                                  OffsetDateTime updatedAt) {
    public KnowledgeCaseRecord {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
