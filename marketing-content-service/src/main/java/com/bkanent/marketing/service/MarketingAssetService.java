package com.bkanent.marketing.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.marketing.entity.MarketingContentEntity;
import com.bkanent.marketing.model.MarketingContentDetailResponse;
import com.bkanent.marketing.model.MarketingContentSearchRequest;
import com.bkanent.marketing.model.MarketingContentUpsertRequest;
import com.bkanent.marketing.model.MarketingPublishStatusUpdateRequest;

import java.util.List;

/**
 * 营销素材服务接口。
 */
public interface MarketingAssetService extends IService<MarketingContentEntity> {

    /**
     * 业务方法：saveContents。
     */
    List<MarketingContentDTO> saveContents(List<MarketingContentDTO> contents);

    /**
     * 业务方法：listByListingId。
     */
    List<MarketingContentDTO> listByListingId(Long listingId);

    void bindGeneratedAssets(Long contentId,
                             List<String> assetUrls,
                             String coverImageUrl,
                             String videoUrl,
                             String updateMessage);

    /**
     * 业务方法：createContent。
     */
    MarketingContentDetailResponse createContent(MarketingContentUpsertRequest request);

    /**
     * 业务方法：createPlatformVariant。
     */
    MarketingContentDetailResponse createPlatformVariant(Long sourceContentId, MarketingContentUpsertRequest request);

    /**
     * 业务方法：updatePublishStatus。
     */
    MarketingContentDetailResponse updatePublishStatus(Long contentId, MarketingPublishStatusUpdateRequest request);

    /**
     * 业务方法：searchContents。
     */
    List<MarketingContentDetailResponse> searchContents(MarketingContentSearchRequest request);
}
