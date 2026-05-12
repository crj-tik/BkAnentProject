package com.bkanent.listing.model;

import java.util.List;

/**
 * ListingAssetBindRequest 房源媒资绑定请求对象。
 */
public record ListingAssetBindRequest(
        /** 业务属性：imageUrls。 */
        List<String> imageUrls,
        /** 业务属性：floorPlanUrls。 */
        List<String> floorPlanUrls,
        /** 业务属性：videoUrls。 */
        List<String> videoUrls
) {
}
