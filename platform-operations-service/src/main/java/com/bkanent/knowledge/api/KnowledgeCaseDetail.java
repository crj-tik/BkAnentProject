package com.bkanent.knowledge.api;

import com.bkanent.common.knowledge.KnowledgeBody;
import com.bkanent.common.knowledge.KnowledgeSourceRef;

import java.time.OffsetDateTime;
import java.util.List;

public record KnowledgeCaseDetail(String knowledgeId, String scopeType, String title, String problem,
                                  String scene, List<String> tags, String state, String currentRevision,
                                  String publishedRevision, String dataMode, long version,
                                  String contentHash, String summary, KnowledgeBody body,
                                  List<KnowledgeSourceRef> sourceRefs, OffsetDateTime updatedAt) {
    public KnowledgeCaseDetail {
        tags = tags == null ? List.of() : List.copyOf(tags);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
