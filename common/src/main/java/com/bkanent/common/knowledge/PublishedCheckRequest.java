package com.bkanent.common.knowledge;

import java.util.List;

public record PublishedCheckRequest(String contractVersion, List<KnowledgeRef> refs) {
    public PublishedCheckRequest {
        refs = refs == null ? List.of() : List.copyOf(refs);
    }
}
