package com.bkanent.listing.service.impl;

import com.bkanent.common.model.KnowledgeDocument;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.listing.rag.ListingRagOutbox;
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
    private final ListingRagOutbox outbox;
    private final com.bkanent.listing.rag.ListingRagIdentity identity;

    /**
     * 构造 ListingManagementServiceImpl 实例。
     */
    public ListingManagementServiceImpl(ListingDomainService listingDomainService,
                                        ListingConverter listingConverter,
                                        ListingSearchService listingSearchService,
                                        ListingKnowledgeDocumentAssembler listingKnowledgeDocumentAssembler,
                                        ListingRagOutbox outbox,
                                        com.bkanent.listing.rag.ListingRagIdentity identity) {
        this.identity = identity;
        this.listingDomainService = listingDomainService;
        this.listingConverter = listingConverter;
        this.listingSearchService = listingSearchService;
        this.listingKnowledgeDocumentAssembler = listingKnowledgeDocumentAssembler;
        this.outbox = outbox;
    }

    /**
     * 创建房源。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse createListing(ListingUpsertRequest request) {
        requireOperation("listing.create");
        long brokerId = com.bkanent.common.authz.AuthzRequestScope.requireBrokerIdentity();
        requireSubmittedBroker(request.brokerId(), brokerId);
        currentReader();
        ListingEntity entity = new ListingEntity();
        entity.setBrokerId(brokerId);
        applyUpsert(entity, request);
        entity.setStatus(ListingStatusEnum.PENDING_REVIEW.name());
        entity.setOcrStatus("PENDING");
        entity.setVerificationStatus(ListingVerificationStatusEnum.PENDING.name());
        listingDomainService.save(entity);
        outbox.publishCurrent(entity.getId());
        log.info("新增房源成功，listingId={}，title={}", entity.getId(), entity.getTitle());
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 更新房源。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse updateListing(Long listingId, ListingUpsertRequest request) {
        requireOperation("listing.update.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.update.own", "listing.update.all");
        if (request.brokerId() != null && !request.brokerId().equals(entity.getBrokerId())) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
        applyUpsert(entity, request);
        listingDomainService.updateById(entity);
        outbox.publishCurrent(listingId);
        log.info("更新房源成功，listingId={}", listingId);
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 删除房源。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteListing(Long listingId) {
        requireOperation("listing.delete.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.delete.own", "listing.delete.all");
        listingDomainService.removeById(listingId);
        outbox.publishCurrent(listingId);
        log.info("删除房源成功，listingId={}", listingId);
    }

    /**
     * 获取房源详情。
     */
    @Override
    public ListingDetailResponse getListingDetail(Long listingId) {
        requireReadPermission();
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.read", "listing.read.all");
        requireReadable(entity, currentReader());
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 查询房源详情列表。
     */
    @Override
    public List<ListingDetailResponse> searchListings(ListingQueryRequest request) {
        request = authorizedQuery(request, "listing.read", "listing.read.all");
        var principal = currentReader();
        if (com.bkanent.listing.rag.ListingRagIdentity.activeOnly(principal)) {
            if (request != null && StringUtils.hasText(request.status()) && !"ON_SALE".equals(request.status())) {
                return List.of();
            }
            request = request == null ? new ListingQueryRequest(null, "ON_SALE", null, null, null, null, null, null)
                    : new ListingQueryRequest(request.keyword(), "ON_SALE", request.brokerId(), request.minArea(),
                    request.maxArea(), request.minTotalPrice(), request.maxTotalPrice(), request.verifiedOnly());
        }
        var rows = listingDomainService.search(request);
        principal = currentReader();
        var current = principal;
        List<ListingDetailResponse> result = new java.util.ArrayList<>();
        for (ListingEntity row : rows) {
            if (com.bkanent.common.authz.AuthzRequestScope.present()) requireOwner(row, "listing.read", "listing.read.all");
            if (com.bkanent.listing.rag.ListingRagIdentity.canRead(current, row)) result.add(listingConverter.toDetailResponse(row));
        }
        return List.copyOf(result);
    }

    /**
     * 查询房源摘要列表。
     */
    @Override
    public List<ListingDTO> searchListingSummaries(String keyword) {
        ListingQueryRequest query = authorizedSummaryQuery(new ListingQueryRequest(keyword, null, null,
                null, null, null, null, null));
        var principal = currentReader();
        var rows = listingDomainService.search(new ListingQueryRequest(keyword,
                com.bkanent.listing.rag.ListingRagIdentity.activeOnly(principal) ? "ON_SALE" : null,
                query.brokerId(), null, null, null, null, null));
        var current = currentReader();
        List<ListingDTO> result = new java.util.ArrayList<>();
        for (ListingEntity row : rows) {
            if (com.bkanent.listing.rag.ListingRagIdentity.canRead(current, row)) result.add(listingConverter.toDto(row));
        }
        return List.copyOf(result);
    }

    /**
     * 使用授权 RAG 默认混合重排查询房源摘要候选。
     */
    @Override
    public List<ListingKeywordSearchResultDTO> searchListingSummariesByKeyword(ListingKeywordSearchRequest request) {
        requireReadPermission();
        return listingSearchService.searchByKeyword(request);
    }

    /**
     * 获取房源摘要。
     */
    @Override
    public ListingDTO getListingSummary(Long listingId) {
        requireReadPermission();
        var principal = currentReader();
        ListingEntity entity = listingDomainService.getById(listingId);
        var current = currentReader();
        if (!principal.userId().equals(current.userId())) {
            throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_FAILED);
        }
        if (entity == null) { return null; }
        requireReadable(entity, current);
        return listingConverter.toDto(entity);
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
        requireOperation("listing.status.update.own");
        if (!ListingStatusEnum.contains(request.status())) {
            throw new IllegalArgumentException("房源状态不合法: " + request.status());
        }
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.status.update.own", "listing.status.update.all");
        entity.setStatus(request.status().toUpperCase());
        listingDomainService.updateById(entity);
        outbox.publishCurrent(listingId);
        log.info("更新房源状态成功，listingId={}，status={}", listingId, entity.getStatus());
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 绑定房源资源。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse bindAssets(Long listingId, ListingAssetBindRequest request) {
        requireOperation("listing.assets.bind.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.assets.bind.own", "listing.assets.bind.all");
        entity.setImageUrls(listingConverter.joinValues(request.imageUrls()));
        entity.setFloorPlanUrls(listingConverter.joinValues(request.floorPlanUrls()));
        entity.setVideoUrls(listingConverter.joinValues(request.videoUrls()));
        listingDomainService.updateById(entity);
        outbox.publishCurrent(listingId);
        log.info("绑定房源媒资成功，listingId={}", listingId);
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 执行房源 OCR。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse recognizeOcr(Long listingId, ListingOcrRecognizeRequest request) {
        requireOperation("listing.ocr.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.ocr.own", "listing.ocr.all");
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
        outbox.publishCurrent(listingId);
        log.info("完成房源 OCR 识别，listingId={}", listingId);
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 核验房源真实性。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse verifyAuthenticity(Long listingId, ListingVerifyRequest request) {
        requireOperation("listing.verify.own");
        outbox.lock(listingId);
        ListingEntity entity = requireListing(listingId);
        requireOwner(entity, "listing.verify.own", "listing.verify.all");
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
        outbox.publishCurrent(listingId);
        log.info("完成房源真实性核验，listingId={}，status={}", listingId, entity.getVerificationStatus());
        return listingConverter.toDetailResponse(entity);
    }

    /**
     * 应用upsert字段。
     */
    private void applyUpsert(ListingEntity entity, ListingUpsertRequest request) {
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
        currentReader();
        ListingEntity entity = listingDomainService.getById(listingId);
        if (entity == null || !Integer.valueOf(0).equals(entity.getDeleted())) {
            throw new IllegalArgumentException("房源不存在: " + listingId);
        }
        return entity;
    }

    private com.bkanent.common.rag.AuthRagPrincipal currentReader() {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            return identity.requireReader(null);
        }
        return identity.requireReader(com.bkanent.listing.rag.ListingRagCredentialContext.current());
    }

    private void requireOperation(String operation) {
        if (!com.bkanent.common.authz.AuthzRequestScope.present()) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
        }
        com.bkanent.common.authz.AuthzRequestScope.requireOperation(operation);
    }

    private void requireReadPermission() {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            if (com.bkanent.common.authz.AuthzRequestScope.businessAdmin()) {
                com.bkanent.common.authz.AuthzRequestScope.requireOperation("listing.read.all");
            } else {
                com.bkanent.common.authz.AuthzRequestScope.requireOperation("listing.read");
            }
        }
    }

    private ListingQueryRequest authorizedQuery(ListingQueryRequest request, String ownOperation, String allOperation) {
        if (!com.bkanent.common.authz.AuthzRequestScope.present()) return request;
        if (com.bkanent.common.authz.AuthzRequestScope.businessAdmin()) {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation(allOperation);
            return request;
        }
        com.bkanent.common.authz.AuthzRequestScope.requireOperation(ownOperation);
        long brokerId = com.bkanent.common.authz.AuthzRequestScope.requireBrokerIdentity();
        if (request != null && request.brokerId() != null && request.brokerId() != brokerId) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
        return request == null ? new ListingQueryRequest(null, null, brokerId, null, null, null, null, null)
                : new ListingQueryRequest(request.keyword(), request.status(), brokerId, request.minArea(),
                request.maxArea(), request.minTotalPrice(), request.maxTotalPrice(), request.verifiedOnly());
    }

    /** Public listing summaries keep the established ON_SALE browse range; private detail routes use owner checks. */
    private ListingQueryRequest authorizedSummaryQuery(ListingQueryRequest request) {
        if (!com.bkanent.common.authz.AuthzRequestScope.present()) return request;
        if (com.bkanent.common.authz.AuthzRequestScope.businessAdmin()) {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation("listing.read.all");
        } else {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation("listing.read");
        }
        return request;
    }

    private void requireOwner(ListingEntity entity, String ownOperation, String allOperation) {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            com.bkanent.common.authz.AuthzRequestScope.requireBrokerOwnership(entity.getBrokerId(), ownOperation, allOperation);
        }
    }

    private void requireSubmittedBroker(Long submittedBrokerId, long trustedBrokerId) {
        if (submittedBrokerId != null && submittedBrokerId.longValue() != trustedBrokerId) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    private void requireReadable(ListingEntity entity, com.bkanent.common.rag.AuthRagPrincipal principal) {
        if (!com.bkanent.listing.rag.ListingRagIdentity.canRead(principal, entity)) {
            throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.FORBIDDEN);
        }
    }
}
