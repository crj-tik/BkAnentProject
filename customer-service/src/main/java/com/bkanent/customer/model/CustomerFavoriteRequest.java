package com.bkanent.customer.model;

/**
 * 客户收藏房源请求。
 */
public record CustomerFavoriteRequest(
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：favoriteSource。 */
        String favoriteSource,
        /** 业务属性：remark。 */
        String remark
) {
}
