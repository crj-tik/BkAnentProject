package com.bkanent.listing.service;

import com.bkanent.common.model.KnowledgeDocument;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.listing.model.ListingAssetBindRequest;
import com.bkanent.listing.model.ListingBuyerSearchQuery;
import com.bkanent.listing.model.ListingBuyerSearchResponse;
import com.bkanent.listing.model.ListingBuyerViewResponse;
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

    /**
     * 确认存量房源的业主档案关联（先验证业主档案当前授权与类型）。
     */
    ListingDetailResponse confirmOwner(Long listingId, Long ownerCustomerId);

    /**
     * 解除存量房源的业主档案关联（保留全部历史，不删除任何记录）。
     */
    ListingDetailResponse unbindOwner(Long listingId);

    /**
     * 历史素材迁移登记（受权操作 listing.assets.bind.own/.all）：旧逗号拼接 URL 与证件/合同 URL
     * 逐条登记为 PENDING_REVIEW 行（图片/户型图/视频=BUYER，产权证/合同=SENSITIVE），
     * 幂等（同 storageKey 跳过），不抓取任何 URL；返回新登记行数。
     */
    Integer registerLegacyAssets(Long listingId);

    /**
     * 退役单条素材（受权操作 listing.assets.bind.own/.all + requireOwner，逻辑删除）。
     */
    void retireAsset(Long assetId);

    /**
     * 买方可见在售房源分页搜索（requireReadPermission + ON_SALE 公开范围）；
     * 查询只读，不创建任何咨询或客户档案。
     */
    ListingBuyerSearchResponse searchBuyerListings(ListingBuyerSearchQuery query);

    /**
     * 买方可见房源详情（requireReadPermission + ON_SALE 公开范围）；
     * 输出前复核当前状态，非在售或不可读返回 null，不泄露受限详情。
     */
    ListingBuyerViewResponse getBuyerDetail(Long listingId);
}
