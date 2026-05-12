package com.bkanent.promotion.model;

import java.util.List;

/**
 * 品牌素材新增或更新请求。
 */
public record BrandAssetUpsertRequest(
        /** 业务属性：assetType。 */
        String assetType,
        /** 业务属性：assetName。 */
        String assetName,
        /** 业务属性：assetUrl。 */
        String assetUrl,
        /** 业务属性：platformScope。 */
        String platformScope,
        /** 业务属性：tags。 */
        List<String> tags,
        /** 业务属性：status。 */
        String status,
        /** 业务属性：remark。 */
        String remark
) {
}
