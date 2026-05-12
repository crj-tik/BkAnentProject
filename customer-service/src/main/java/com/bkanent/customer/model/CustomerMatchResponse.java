package com.bkanent.customer.model;

import com.bkanent.common.model.ListingDTO;

/**
 * 房客匹配响应。
 */
public record CustomerMatchResponse(
        /** 业务属性：customerId。 */
        Long customerId,
        /** 业务属性：listing。 */
        ListingDTO listing,
        /** 业务属性：matchReason。 */
        String matchReason,
        /** 业务属性：matchScore。 */
        Integer matchScore
) {
}
