package com.bkanent.common.knowledge;

import java.time.OffsetDateTime;
import java.util.List;

public record PublishedBatchReadItem(KnowledgeRef knowledgeRef, String status, String publicationState,
                                     OffsetDateTime observedAt, String dataMode, String datasetId,
                                     List<KnowledgeSourceRef> sourceRefs, KnowledgeBody body) {
    public PublishedBatchReadItem {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
