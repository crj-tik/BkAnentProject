package com.bkanent.listing.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * ListingUpsertRequest 房源新增或更新请求对象。
 */
public record ListingUpsertRequest(
        /** 业务属性：brokerId。 */
        Long brokerId,
        /** 业务属性：title。 */
        String title,
        /** 业务属性：address。 */
        String address,
        /** 业务属性：layout。 */
        String layout,
        /** 业务属性：area。 */
        BigDecimal area,
        /** 业务属性：totalPrice。 */
        BigDecimal totalPrice,
        /** 业务属性：floorLevel。 */
        String floorLevel,
        /** 业务属性：decoration。 */
        String decoration,
        /** 业务属性：schoolZone。 */
        String schoolZone,
        /** 业务属性：traffic。 */
        String traffic,
        /** 业务属性：ownerName。 */
        String ownerName,
        /** 业务属性：certificateNo。 */
        String certificateNo,
        /** 业务属性：propertyCertificateUrl。 */
        String propertyCertificateUrl,
        /** 业务属性：contractUrl。 */
        String contractUrl,
        /** 业务属性：imageUrls。 */
        List<String> imageUrls,
        /** 业务属性：floorPlanUrls。 */
        List<String> floorPlanUrls,
        /** 业务属性：videoUrls。 */
        List<String> videoUrls
) {
}
