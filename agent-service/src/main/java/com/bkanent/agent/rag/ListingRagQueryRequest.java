package com.bkanent.agent.rag;

public record ListingRagQueryRequest(
        String query,
        Integer topK,
        String collectionName
) {
}
