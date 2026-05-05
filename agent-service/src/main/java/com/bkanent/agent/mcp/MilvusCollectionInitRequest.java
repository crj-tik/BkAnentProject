package com.bkanent.agent.mcp;

public record MilvusCollectionInitRequest(
        String collectionName,
        Integer dimension
) {
}
