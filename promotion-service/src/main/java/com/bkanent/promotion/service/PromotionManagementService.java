package com.bkanent.promotion.service;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.promotion.model.BrandAssetResponse;
import com.bkanent.promotion.model.BrandAssetUpsertRequest;
import com.bkanent.promotion.model.PromotionEffectResponse;
import com.bkanent.promotion.model.PromotionEffectSyncRequest;
import com.bkanent.promotion.model.PromotionPublishRequest;
import com.bkanent.promotion.model.PromotionPublishResultResponse;
import com.bkanent.promotion.model.PromotionRoiReportResponse;

import java.util.List;

/**
 * 宣传业务管理服务接口。
 */
public interface PromotionManagementService {

    /**
     * 业务方法：publishContent。
     */
    PromotionPublishResultResponse publishContent(MarketingContentDTO content, PromotionPublishRequest request);

    /**
     * 业务方法：syncEffectStat。
     */
    PromotionEffectResponse syncEffectStat(PromotionEffectSyncRequest request);

    /**
     * 业务方法：listEffectStats。
     */
    List<PromotionEffectResponse> listEffectStats(Long listingId);

    /**
     * 业务方法：buildRoiReport。
     */
    List<PromotionRoiReportResponse> buildRoiReport();

    /**
     * 业务方法：saveBrandAsset。
     */
    BrandAssetResponse saveBrandAsset(BrandAssetUpsertRequest request);

    /**
     * 业务方法：listBrandAssets。
     */
    List<BrandAssetResponse> listBrandAssets(String assetType, String keyword);
}
