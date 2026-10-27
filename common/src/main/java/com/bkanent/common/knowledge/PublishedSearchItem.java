package com.bkanent.common.knowledge;

import java.time.OffsetDateTime;
import java.util.List;

public record PublishedSearchItem(KnowledgeRef knowledgeRef, String publicationState,
                                 OffsetDateTime observedAt, String dataMode, String datasetId,
                                 List<KnowledgeSourceRef> sourceRefs, String summary) {
    public PublishedSearchItem {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
