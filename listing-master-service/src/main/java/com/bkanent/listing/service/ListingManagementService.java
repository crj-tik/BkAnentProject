package com.bkanent.listing.service;

import com.bkanent.common.model.KnowledgeDocument;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.listing.model.ListingAssetBindRequest;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.model.ListingOcrRecognizeRequest;
import com.bkanent.listing.model.ListingQueryRequest;
import com.bkanent.listing.model.ListingStatusUpdateRequest;
import com.bkanent.listing.model.ListingUpsertRequest;
import com.bkanent.listing.model.ListingVerifyRequest;

import java.util.List;

/**
 * ListingManagementService 服务接口。
 */
public interface ListingManagementService {

    /**
     * 创建房源。
     */
    ListingDetailResponse createListing(ListingUpsertRequest request);

    /**
     * 更新房源。
     */
    ListingDetailResponse updateListing(Long listingId, ListingUpsertRequest request);

    /**
     * 删除房源。
     */
    void deleteListing(Long listingId);

    /**
     * 获取房源详情。
     */
    ListingDetailResponse getListingDetail(Long listingId);

    /**
     * 查询房源详情列表。
     */
    List<ListingDetailResponse> searchListings(ListingQueryRequest request);

    /**
     * 查询房源摘要列表。
     */
    List<ListingDTO> searchListingSummaries(String keyword);

    /**
     * 使用 ES BM25 查询房源摘要候选。
     */
    List<ListingKeywordSearchResultDTO> searchListingSummariesByKeyword(ListingKeywordSearchRequest request);

    /**
     * 获取房源摘要。
     */
    ListingDTO getListingSummary(Long listingId);

    /**
     * 获取房源知识文档。
     */
    KnowledgeDocument getListingKnowledgeDocument(Long listingId);

    /**
     * 更新房源状态。
     */
    ListingDetailResponse updateStatus(Long listingId, ListingStatusUpdateRequest request);

    /**
     * 绑定房源资源。
     */
    ListingDetailResponse bindAssets(Long listingId, ListingAssetBindRequest request);

    /**
     * 执行房源 OCR。
     */
    ListingDetailResponse recognizeOcr(Long listingId, ListingOcrRecognizeRequest request);

    /**
     * 核验房源真实性。
     */
    ListingDetailResponse verifyAuthenticity(Long listingId, ListingVerifyRequest request);
}
