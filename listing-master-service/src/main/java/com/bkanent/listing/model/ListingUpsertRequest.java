package com.bkanent.listing.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * ListingUpsertRequest 房源新增或更新请求对象。
 * ownerCustomerId 为内部业主档案 ID（新登记必填，仅存量可空，非产权证明）；
 * requestId 为登记幂等键（可空，同键同内容重试返回原房源，不同内容拒绝）。
 */
public record ListingUpsertRequest(
        /** 业务属性：brokerId（仅作提交校验，归属以可信身份为准）。 */
        Long brokerId,
        /** 业务属性：ownerCustomerId（Customer 域业主档案 ID）。 */
        Long ownerCustomerId,
        /** 业务属性：requestId（登记幂等键）。 */
        String requestId,
        /** 业务属性：title。 */
        String title,
        /** 业务属性：address。 */
        String address,
        /** 业务属性：communityName。 */
        String communityName,
        /** 业务属性：districtCode。 */
        String districtCode,
        /** 业务属性：layout。 */
        String layout,
        /** 业务属性：area。 */
        BigDecimal area,
        /** 业务属性：totalPrice（兼容期万元字段；内部主值为 askingPriceYuan）。 */
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
