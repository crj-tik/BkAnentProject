package com.bkanent.listing.model;

/**
 * ListingAssetUpsertItem 房源素材逐项绑定条目。
 * assetType: IMAGE/FLOOR_PLAN/VIDEO/CERTIFICATE/CONTRACT；visibility: BUYER/INTERNAL/SENSITIVE；
 * status 为空时默认 ACTIVE；sortOrder 为空时按同类型列表顺序填充。
 * storageKey 保留原值，服务端不抓取任何 URL。
 */
public record ListingAssetUpsertItem(
        /** 业务属性：assetType（素材类型）。 */
        String assetType,
        /** 业务属性：storageKey（受管文件键或 URL 原值）。 */
        String storageKey,
        /** 业务属性：visibility（可见性）。 */
        String visibility,
        /** 业务属性：sortOrder（同类型内顺序，可空）。 */
        Integer sortOrder,
        /** 业务属性：status（ACTIVE/PENDING_REVIEW/RETIRED，可空默认 ACTIVE）。 */
        String status
) {
}
