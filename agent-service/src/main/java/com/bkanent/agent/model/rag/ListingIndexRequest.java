package com.bkanent.agent.model.rag;

/**
 * 房源索引请求对象。
 */
public record ListingIndexRequest(
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：collectionName。 */
        String collectionName
) {
}
