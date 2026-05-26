package com.bkanent.agent.model.rag;

/**
 * ListingIndexRequest 数据对象。
 */
public record ListingIndexRequest(
        String userId,
        Long listingId,
        String collectionName
) {
}
