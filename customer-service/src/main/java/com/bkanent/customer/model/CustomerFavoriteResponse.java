package com.bkanent.customer.model;

/**
 * 客户收藏房源响应。
 */
public record CustomerFavoriteResponse(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：customerId。 */
        Long customerId,
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：favoriteSource。 */
        String favoriteSource,
        /** 业务属性：remark。 */
        String remark
) {
}
