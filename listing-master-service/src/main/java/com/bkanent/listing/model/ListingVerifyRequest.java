package com.bkanent.listing.model;

/**
 * ListingVerifyRequest 房源真实性核验请求对象。
 */
public record ListingVerifyRequest(
        /** 业务属性：externalSource。 */
        String externalSource,
        /** 业务属性：certificateNo。 */
        String certificateNo,
        /** 业务属性：ownerName。 */
        String ownerName
) {
}
