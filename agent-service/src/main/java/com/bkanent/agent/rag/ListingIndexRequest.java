package com.bkanent.agent.rag;

public record ListingIndexRequest(
        Long listingId,
        String collectionName
) {
}
