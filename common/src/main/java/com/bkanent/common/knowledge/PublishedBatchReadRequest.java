package com.bkanent.common.knowledge;

import java.util.List;

public record PublishedBatchReadRequest(String contractVersion, List<KnowledgeRef> refs) {
    public PublishedBatchReadRequest {
        refs = refs == null ? List.of() : List.copyOf(refs);
    }
}
