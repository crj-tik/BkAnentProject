package com.bkanent.listing.model;

/**
 * ListingOwnerConfirmRequest 存量房源业主确认请求对象。
 * ownerCustomerId 为 Customer 域业主档案 ID，非产权证明。
 */
public record ListingOwnerConfirmRequest(
        /** 业务属性：ownerCustomerId。 */
        Long ownerCustomerId
) {
}
