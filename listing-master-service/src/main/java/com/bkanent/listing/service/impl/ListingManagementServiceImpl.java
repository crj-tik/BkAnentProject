package com.bkanent.listing.service.impl;

import com.bkanent.common.model.KnowledgeDocument;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.common.rpc.AgentRpcService;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.enums.ListingStatusEnum;
import com.bkanent.listing.enums.ListingVerificationStatusEnum;
import com.bkanent.listing.model.ListingAssetBindRequest;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.model.ListingOcrRecognizeRequest;
import com.bkanent.listing.model.ListingQueryRequest;
import com.bkanent.listing.model.ListingStatusUpdateRequest;
import com.bkanent.listing.model.ListingUpsertRequest;
import com.bkanent.listing.model.ListingVerifyRequest;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.bkanent.listing.service.ListingDomainService;
import com.bkanent.listing.service.ListingManagementService;
import com.bkanent.listing.service.ListingSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ListingManagementServiceImpl 服务实现类。
 */
@Service
public class ListingManagementServiceImpl implements ListingManagementService {

    /**
     * 字段：log。
     */
    private static final Logger log = LoggerFactory.getLogger(ListingManagementServiceImpl.class);
    /**
     * 字段：listingDomainService。
     */
    private final ListingDomainService listingDomainService;
    /**
     * 字段：listingConverter。
     */
    private final ListingConverter listingConverter;
    /**
     * 字段：listingSearchService。
     */
    private final ListingSearchService listingSearchService;
    /**
     * 字段：listingKnowledgeDocumentAssembler。
     */
    private final ListingKnowledgeDocumentAssembler listingKnowledgeDocumentAssembler;
    private final AgentRpcService agentRpcService;

    /**
     * 构造 ListingManagementServiceImpl 实例。
     */
    public ListingManagementServiceImpl(ListingDomainService listingDomainService,
                                        ListingConverter listingConverter,
                                        ListingSearchService listingSearchService,
                                        ListingKnowledgeDocumentAssembler listingKnowledgeDocumentAssembler,
                                        ObjectProvider<AgentRpcService> agentRpcServiceProvider) {
        this.listingDomainService = listingDomainService;
        this.listingConverter = listingConverter;
        this.listingSearchService = listingSearchService;
        this.listingKnowledgeDocumentAssembler = listingKnowledgeDocumentAssembler;
        this.agentRpcService = agentRpcServiceProvider.getIfAvailable();
    }

    /**
     * 创建房源。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse createListing(ListingUpsertRequest request) {
        ListingEntity entity = new ListingEntity();
        applyUpsert(entity, request);
        entity.setStatus(ListingStatusEnum.PENDING_REVIEW.name());
        entity.setOcrStatus("PENDING");
        entity.setVerificationStatus(ListingVerificationStatusEnum.PENDING.name());
        listingDomainService.save(entity);
        listingSearchService.indexListing(entity);
        syncListingKnowledge(entity.getId());
        log.info("新增房源成功，listingId={}，title={}", entity.getId(), entity.getTitle());
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 更新房源。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse updateListing(Long listingId, ListingUpsertRequest request) {
        ListingEntity entity = requireListing(listingId);
        applyUpsert(entity, request);
        listingDomainService.updateById(entity);
        listingSearchService.indexListing(entity);
        syncListingKnowledge(listingId);
        log.info("更新房源成功，listingId={}", listingId);
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 删除房源。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteListing(Long listingId) {
        requireListing(listingId);
        listingDomainService.removeById(listingId);
        listingSearchService.deleteListing(listingId);
        deleteListingKnowledge(listingId);
        log.info("删除房源成功，listingId={}", listingId);
    }

    /**
     * 获取房源详情。
     */
    @Override
    public ListingDetailResponse getListingDetail(Long listingId) {
        return listingConverter.toDetailResponse(requireListing(listingId));
    }

    /**
     * 查询房源详情列表。
     */
    @Override
    public List<ListingDetailResponse> searchListings(ListingQueryRequest request) {
        return listingDomainService.search(request).stream()
                .map(listingConverter::toDetailResponse)
                .toList();
    }

    /**
     * 查询房源摘要列表。
     */
    @Override
    public List<ListingDTO> searchListingSummaries(String keyword) {
        return listingDomainService.searchByKeyword(keyword).stream()
                .map(listingConverter::toDto)
                .toList();
    }

    /**
     * 使用 ES BM25 查询房源摘要候选。
     */
    @Override
    public List<ListingKeywordSearchResultDTO> searchListingSummariesByKeyword(ListingKeywordSearchRequest request) {
        return listingSearchService.searchByKeyword(request);
    }

    /**
     * 获取房源摘要。
     */
    @Override
    public ListingDTO getListingSummary(Long listingId) {
        return listingConverter.toDto(listingDomainService.getById(listingId));
    }

    /**
     * 获取房源知识文档。
     */
    @Override
    public KnowledgeDocument getListingKnowledgeDocument(Long listingId) {
        ListingDTO listing = getListingSummary(listingId);
        return listing == null ? null : listingKnowledgeDocumentAssembler.assemble(listing);
    }

    /**
     * 更新房源状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse updateStatus(Long listingId, ListingStatusUpdateRequest request) {
        if (!ListingStatusEnum.contains(request.status())) {
            throw new IllegalArgumentException("房源状态不合法: " + request.status());
        }
        ListingEntity entity = requireListing(listingId);
        entity.setStatus(request.status().toUpperCase());
        listingDomainService.updateById(entity);
        listingSearchService.indexListing(entity);
        syncListingKnowledge(listingId);
        log.info("更新房源状态成功，listingId={}，status={}", listingId, entity.getStatus());
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 绑定房源资源。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse bindAssets(Long listingId, ListingAssetBindRequest request) {
        ListingEntity entity = requireListing(listingId);
        entity.setImageUrls(listingConverter.joinValues(request.imageUrls()));
        entity.setFloorPlanUrls(listingConverter.joinValues(request.floorPlanUrls()));
        entity.setVideoUrls(listingConverter.joinValues(request.videoUrls()));
        listingDomainService.updateById(entity);
        listingSearchService.indexListing(entity);
        syncListingKnowledge(listingId);
        log.info("绑定房源媒资成功，listingId={}", listingId);
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 执行房源 OCR。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse recognizeOcr(Long listingId, ListingOcrRecognizeRequest request) {
        ListingEntity entity = requireListing(listingId);
        entity.setPropertyCertificateUrl(request.propertyCertificateUrl());
        entity.setContractUrl(request.contractUrl());
        entity.setOcrStatus("DONE");
        if (!StringUtils.hasText(entity.getCertificateNo())) {
            entity.setCertificateNo("OCR-" + listingId);
        }
        if (!StringUtils.hasText(entity.getOwnerName())) {
            entity.setOwnerName("OCR识别业主" + listingId);
        }
        listingDomainService.updateById(entity);
        listingSearchService.indexListing(entity);
        syncListingKnowledge(listingId);
        log.info("完成房源 OCR 识别，listingId={}", listingId);
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 核验房源真实性。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse verifyAuthenticity(Long listingId, ListingVerifyRequest request) {
        ListingEntity entity = requireListing(listingId);
        if (StringUtils.hasText(request.certificateNo())) {
            entity.setCertificateNo(request.certificateNo());
        }
        if (StringUtils.hasText(request.ownerName())) {
            entity.setOwnerName(request.ownerName());
        }
        entity.setVerificationSource(StringUtils.hasText(request.externalSource()) ? request.externalSource() : "mock-third-party");

        boolean verified = StringUtils.hasText(entity.getCertificateNo()) && StringUtils.hasText(entity.getOwnerName());
        entity.setVerificationStatus(verified
                ? ListingVerificationStatusEnum.VERIFIED.name()
                : ListingVerificationStatusEnum.FAILED.name());
        entity.setVerificationRemark(verified ? "房源信息校验通过" : "证号或业主信息缺失，校验失败");
        listingDomainService.updateById(entity);
        listingSearchService.indexListing(entity);
        syncListingKnowledge(listingId);
        log.info("完成房源真实性核验，listingId={}，status={}", listingId, entity.getVerificationStatus());
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 应用upsert字段。
     */
    private void applyUpsert(ListingEntity entity, ListingUpsertRequest request) {
        entity.setBrokerId(request.brokerId());
        entity.setTitle(request.title());
        entity.setAddress(request.address());
        entity.setLayout(request.layout());
        entity.setArea(request.area());
        entity.setTotalPrice(request.totalPrice());
        entity.setFloorLevel(request.floorLevel());
        entity.setDecoration(request.decoration());
        entity.setSchoolZone(request.schoolZone());
        entity.setTraffic(request.traffic());
        entity.setOwnerName(request.ownerName());
        entity.setCertificateNo(request.certificateNo());
        entity.setPropertyCertificateUrl(request.propertyCertificateUrl());
        entity.setContractUrl(request.contractUrl());
        entity.setImageUrls(listingConverter.joinValues(request.imageUrls()));
        entity.setFloorPlanUrls(listingConverter.joinValues(request.floorPlanUrls()));
        entity.setVideoUrls(listingConverter.joinValues(request.videoUrls()));
    }

    /**
     * 获取必需房源。
     */
    private ListingEntity requireListing(Long listingId) {
        ListingEntity entity = listingDomainService.getById(listingId);
        if (entity == null) {
            throw new IllegalArgumentException("房源不存在: " + listingId);
        }
        return entity;
    }

    private void syncListingKnowledge(Long listingId) {
        if (agentRpcService == null || listingId == null) {
            return;
        }
        try {
            agentRpcService.syncListingKnowledge(listingId);
        }
        catch (Exception exception) {
            log.warn("房源同步 Milvus 失败，listingId={}，原因={}", listingId, exception.getMessage());
        }
    }

    private void deleteListingKnowledge(Long listingId) {
        if (agentRpcService == null || listingId == null) {
            return;
        }
        try {
            agentRpcService.deleteListingKnowledge(listingId);
        }
        catch (Exception exception) {
            log.warn("房源删除 Milvus 文档失败，listingId={}，原因={}", listingId, exception.getMessage());
        }
    }
}
