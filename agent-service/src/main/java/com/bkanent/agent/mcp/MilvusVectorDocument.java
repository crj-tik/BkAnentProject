package com.bkanent.agent.mcp;

import java.util.List;

public record MilvusVectorDocument(
        String documentId,
        String sourceType,
        String sourceId,
        String content,
        List<Float> vector
) {
}
