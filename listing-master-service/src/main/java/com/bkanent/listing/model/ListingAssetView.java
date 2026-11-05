package com.bkanent.listing.model;

/**
 * ListingAssetView 买方可见素材条目（仅 BUYER 可见性、ACTIVE 状态的素材进入买方投影）。
 */
public record ListingAssetView(
        /** 业务属性：assetType（素材类型）。 */
        String assetType,
        /** 业务属性：storageKey（受管文件键或 URL 原值）。 */
        String storageKey,
        /** 业务属性：sortOrder（展示顺序）。 */
        Integer sortOrder
) {
}
