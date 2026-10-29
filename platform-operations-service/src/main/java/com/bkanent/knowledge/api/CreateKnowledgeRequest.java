package com.bkanent.knowledge.api;

import com.bkanent.common.knowledge.KnowledgeBody;
import java.util.List;

public record CreateKnowledgeRequest(String requestId, String scopeType, Long storeId,
                                    String title, String problem, String scene,
                                    List<String> tags, KnowledgeBody body) {
    public CreateKnowledgeRequest {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
