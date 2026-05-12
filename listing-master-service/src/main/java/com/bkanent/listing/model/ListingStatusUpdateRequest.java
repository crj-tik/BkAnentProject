package com.bkanent.listing.model;

/**
 * ListingStatusUpdateRequest 房源状态更新请求对象。
 */
public record ListingStatusUpdateRequest(
        /** 业务属性：status。 */
        String status
) {
}
