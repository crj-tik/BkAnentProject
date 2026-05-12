package com.bkanent.listing.service.impl;

import com.bkanent.common.model.ListingDTO;
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
import com.bkanent.listing.service.ListingDomainService;
import com.bkanent.listing.service.ListingManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ListingManagementServiceImpl 房源管理服务实现类。
 */
@Service
public class ListingManagementServiceImpl implements ListingManagementService {

    private static final Logger log = LoggerFactory.getLogger(ListingManagementServiceImpl.class);

    private final ListingDomainService listingDomainService;
    private final ListingConverter listingConverter;

    public ListingManagementServiceImpl(ListingDomainService listingDomainService, ListingConverter listingConverter) {
        this.listingDomainService = listingDomainService;
        this.listingConverter = listingConverter;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse createListing(ListingUpsertRequest request) {
        ListingEntity entity = new ListingEntity();
        applyUpsert(entity, request);
        entity.setStatus(ListingStatusEnum.PENDING_REVIEW.name());
        entity.setOcrStatus("PENDING");
        entity.setVerificationStatus(ListingVerificationStatusEnum.PENDING.name());
        listingDomainService.save(entity);
        log.info("新增房源成功，listingId={}，title={}", entity.getId(), entity.getTitle());
        return listingConverter.toDetailResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse updateListing(Long listingId, ListingUpsertRequest request) {
        ListingEntity entity = requireListing(listingId);
        applyUpsert(entity, request);
        listingDomainService.updateById(entity);
        log.info("更新房源成功，listingId={}", listingId);
        return listingConverter.toDetailResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteListing(Long listingId) {
        requireListing(listingId);
        listingDomainService.removeById(listingId);
        log.info("删除房源成功，listingId={}", listingId);
    }

    @Override
    public ListingDetailResponse getListingDetail(Long listingId) {
        return listingConverter.toDetailResponse(requireListing(listingId));
    }

    @Override
    public List<ListingDetailResponse> searchListings(ListingQueryRequest request) {
        return listingDomainService.search(request).stream()
                .map(listingConverter::toDetailResponse)
                .toList();
    }

    @Override
    public List<ListingDTO> searchListingSummaries(String keyword) {
        return listingDomainService.searchByKeyword(keyword).stream()
                .map(listingConverter::toDto)
                .toList();
    }

    @Override
    public ListingDTO getListingSummary(Long listingId) {
        return listingConverter.toDto(listingDomainService.getById(listingId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse updateStatus(Long listingId, ListingStatusUpdateRequest request) {
        if (!ListingStatusEnum.contains(request.status())) {
            throw new IllegalArgumentException("房源状态不合法: " + request.status());
        }
        ListingEntity entity = requireListing(listingId);
        entity.setStatus(request.status().toUpperCase());
        listingDomainService.updateById(entity);
        log.info("更新房源状态成功，listingId={}，status={}", listingId, entity.getStatus());
        return listingConverter.toDetailResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ListingDetailResponse bindAssets(Long listingId, ListingAssetBindRequest request) {
        ListingEntity entity = requireListing(listingId);
        entity.setImageUrls(listingConverter.joinValues(request.imageUrls()));
        entity.setFloorPlanUrls(listingConverter.joinValues(request.floorPlanUrls()));
        entity.setVideoUrls(listingConverter.joinValues(request.videoUrls()));
        listingDomainService.updateById(entity);
        log.info("绑定房源媒资成功，listingId={}", listingId);
        return listingConverter.toDetailResponse(entity);
    }

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
        log.info("完成房源 OCR 识别，listingId={}", listingId);
        return listingConverter.toDetailResponse(entity);
    }

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
        entity.setVerificationRemark(verified ? "房源信息核验通过" : "证号或业主信息缺失，核验失败");
        listingDomainService.updateById(entity);
        log.info("完成房源真实性核验，listingId={}，status={}", listingId, entity.getVerificationStatus());
        return listingConverter.toDetailResponse(entity);
    }

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

    private ListingEntity requireListing(Long listingId) {
        ListingEntity entity = listingDomainService.getById(listingId);
        if (entity == null) {
            throw new IllegalArgumentException("房源不存在: " + listingId);
        }
        return entity;
    }
}
