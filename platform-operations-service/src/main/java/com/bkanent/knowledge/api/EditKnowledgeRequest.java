package com.bkanent.knowledge.api;

import com.bkanent.common.knowledge.KnowledgeBody;
import java.util.List;

public record EditKnowledgeRequest(String requestId, Long expectedVersion, String title,
                                   String problem, String scene, List<String> tags,
                                   KnowledgeBody body) {
    public EditKnowledgeRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
