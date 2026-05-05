package com.bkanent.agent.mcp;

import java.util.List;

public record MilvusUpsertRequest(
        String collectionName,
        List<MilvusVectorDocument> documents
) {
}
