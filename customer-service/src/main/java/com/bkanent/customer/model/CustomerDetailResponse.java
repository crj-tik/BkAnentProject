package com.bkanent.customer.model;

import com.bkanent.common.model.CustomerProfileDTO;

import java.util.List;

/**
 * 客户详情聚合响应。
 */
public record CustomerDetailResponse(
        /** 业务属性：profile。 */
        CustomerProfileDTO profile,
        /** 业务属性：followRecords。 */
        List<CustomerFollowRecordResponse> followRecords,
        /** 业务属性：entrustRecords。 */
        List<OwnerEntrustResponse> entrustRecords,
        /** 业务属性：favoriteListings。 */
        List<CustomerFavoriteResponse> favoriteListings
) {
}
