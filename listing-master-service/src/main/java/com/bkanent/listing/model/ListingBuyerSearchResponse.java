package com.bkanent.listing.model;

import java.util.List;

/**
 * ListingBuyerSearchResponse 买方可见在售房源分页结果。
 * nextCursor 为空表示没有更多页；同条件翻页以 (updatedAt,id) 游标保证不重不漏。
 */
public record ListingBuyerSearchResponse(
        /** 业务属性：items（当前页买方可见投影）。 */
        List<ListingBuyerViewResponse> items,
        /** 业务属性：nextCursor（下一页游标，空表示末页）。 */
        String nextCursor
) {
}
