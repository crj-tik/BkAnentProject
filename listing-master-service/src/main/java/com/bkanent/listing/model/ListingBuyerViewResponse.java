package com.bkanent.listing.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * ListingBuyerViewResponse 买方可见房源投影。
 * 不含 ownerName、certificateNo、业主联系方式、证件与合同 URL 及内部备注；
 * 素材列表仅包含 BUYER 可见、ACTIVE 状态的素材行。
 */
public record ListingBuyerViewResponse(
        /** 业务属性：id（内部房源 ID）。 */
        Long id,
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
        /** 业务属性：area（平方米）。 */
        BigDecimal area,
        /** 业务属性：askingPriceYuan（挂牌价，CNY 元）。 */
        BigDecimal askingPriceYuan,
        /** 业务属性：status。 */
        String status,
        /** 业务属性：floorLevel。 */
        String floorLevel,
        /** 业务属性：decoration。 */
        String decoration,
        /** 业务属性：schoolZone。 */
        String schoolZone,
        /** 业务属性：traffic。 */
        String traffic,
        /** 业务属性：assets（BUYER 可见素材列表）。 */
        List<ListingAssetView> assets
) {
}
