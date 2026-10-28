package com.bkanent.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.marketing.converter.MarketingContentConverter;
import com.bkanent.marketing.entity.MarketingContentEntity;
import com.bkanent.marketing.enums.MarketingAuditStatusEnum;
import com.bkanent.marketing.enums.MarketingContentTypeEnum;
import com.bkanent.marketing.enums.MarketingPublishStatusEnum;
import com.bkanent.marketing.mapper.MarketingContentMapper;
import com.bkanent.marketing.model.MarketingContentDetailResponse;
import com.bkanent.marketing.model.MarketingContentSearchRequest;
import com.bkanent.marketing.model.MarketingContentUpsertRequest;
import com.bkanent.marketing.model.MarketingPublishStatusUpdateRequest;
import com.bkanent.marketing.service.MarketingAssetService;
import com.bkanent.marketing.service.MarketingContentSearchService;
import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 营销素材服务实现。
 */
@Service
public class MarketingAssetServiceImpl extends ServiceImpl<MarketingContentMapper, MarketingContentEntity> implements MarketingAssetService {

    private static final Logger log = LoggerFactory.getLogger(MarketingAssetServiceImpl.class);

    private final MarketingContentConverter marketingContentConverter;
    private final MarketingContentSearchService marketingContentSearchService;

    public MarketingAssetServiceImpl(MarketingContentConverter marketingContentConverter,
                                     MarketingContentSearchService marketingContentSearchService) {
        this.marketingContentConverter = marketingContentConverter;
        this.marketingContentSearchService = marketingContentSearchService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<MarketingContentDTO> saveContents(List<MarketingContentDTO> contents) {
        VerifiedDomainAuthorization.requireOperation("marketing.content.create.own");
        long ownerBrokerId = VerifiedDomainAuthorization.requireBrokerIdentity();
        List<MarketingContentEntity> entities = contents.stream()
                .map(marketingContentConverter::toEntity)
                .peek(entity -> {
                    entity.setOwnerBrokerId(ownerBrokerId);
                    entity.setAuditStatus(MarketingAuditStatusEnum.DRAFT.name());
                    entity.setStatus(MarketingPublishStatusEnum.DRAFT.name());
                })
                .toList();
        saveBatch(entities);
        entities.forEach(marketingContentSearchService::indexContent);
        log.info("批量保存营销内容成功，数量={}", entities.size());
        return entities.stream()
                .map(marketingContentConverter::toDto)
                .toList();
    }

    @Override
    public List<MarketingContentDTO> listByListingId(Long listingId) {
        Long ownerBrokerId = VerifiedDomainAuthorization.requireBrokerScope(
                "marketing.content.read.own", "marketing.content.read.all");
        return list(new LambdaQueryWrapper<MarketingContentEntity>()
                .eq(MarketingContentEntity::getListingId, listingId)
                .eq(ownerBrokerId != null, MarketingContentEntity::getOwnerBrokerId, ownerBrokerId)
                .orderByDesc(MarketingContentEntity::getVersionNo)
                .orderByDesc(MarketingContentEntity::getUpdatedAt))
                .stream()
                .map(marketingContentConverter::toDto)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindGeneratedAssets(Long contentId,
                                    List<String> assetUrls,
                                    String coverImageUrl,
                                    String videoUrl,
                                    String updateMessage) {
        MarketingContentEntity entity = requireContent(contentId,
                "marketing.assets.bind.own", "marketing.assets.bind.all");
        entity.setAssetUrls(marketingContentConverter.joinValues(assetUrls));
        entity.setCoverImageUrl(coverImageUrl);
        entity.setVideoUrl(videoUrl);
        if (StringUtils.hasText(updateMessage)) {
            entity.setPublishMessage(updateMessage);
        }
        updateById(entity);
        marketingContentSearchService.indexContent(entity);
        log.info("回写营销内容生成素材成功，contentId={}，assetCount={}", contentId, assetUrls == null ? 0 : assetUrls.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingContentDetailResponse createContent(MarketingContentUpsertRequest request) {
        VerifiedDomainAuthorization.requireOperation("marketing.content.create.own");
        long ownerBrokerId = VerifiedDomainAuthorization.requireBrokerIdentity();
        validateUpsertRequest(request);
        MarketingContentEntity entity = new MarketingContentEntity();
        applyUpsert(entity, request);
        entity.setOwnerBrokerId(ownerBrokerId);
        entity.setParentContentId(null);
        entity.setVersionNo(1);
        entity.setStatus(MarketingPublishStatusEnum.DRAFT.name());
        save(entity);
        marketingContentSearchService.indexContent(entity);
        log.info("新增营销内容成功，contentId={}，listingId={}", entity.getId(), entity.getListingId());
        return marketingContentConverter.toDetailResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingContentDetailResponse createPlatformVariant(Long sourceContentId, MarketingContentUpsertRequest request) {
        Long ownerBrokerId = VerifiedDomainAuthorization.requireBrokerScope(
                "marketing.content.variant.create.own", "marketing.content.variant.create.all");
        MarketingContentEntity sourceEntity = requireContent(sourceContentId,
                "marketing.content.variant.create.own", "marketing.content.variant.create.all", ownerBrokerId);
        validateUpsertRequest(request);
        MarketingContentEntity entity = new MarketingContentEntity();
        applyUpsert(entity, request);
        entity.setOwnerBrokerId(ownerBrokerId == null
                ? VerifiedDomainAuthorization.requireBrokerIdentity() : ownerBrokerId);
        entity.setParentContentId(sourceContentId);
        entity.setVersionNo(sourceEntity.getVersionNo() == null ? 1 : sourceEntity.getVersionNo() + 1);
        entity.setStatus(MarketingPublishStatusEnum.DRAFT.name());
        save(entity);
        marketingContentSearchService.indexContent(entity);
        log.info("新增营销内容平台适配版本成功，sourceId={}，contentId={}", sourceContentId, entity.getId());
        return marketingContentConverter.toDetailResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingContentDetailResponse updatePublishStatus(Long contentId, MarketingPublishStatusUpdateRequest request) {
        VerifiedDomainAuthorization.requireBrokerScope(
                "marketing.publish.status.update.own", "marketing.publish.status.update.all");
        throw com.bkanent.marketing.campaign.CampaignDomainException.legacyWriteDisabled(
                "publish status is owned by marketing campaign receipts; use the campaign confirm and receipt APIs");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketingContentDetailResponse updateAuditStatus(Long contentId, String auditStatus) {
        Long ownerBrokerId = VerifiedDomainAuthorization.requireBrokerScope(
                "marketing.content.review.own", "marketing.content.review.all");
        if (!MarketingAuditStatusEnum.contains(auditStatus)) {
            throw new IllegalArgumentException("审核状态不合法: " + auditStatus);
        }
        MarketingContentEntity entity = requireContent(contentId,
                "marketing.content.review.own", "marketing.content.review.all", ownerBrokerId);
        entity.setAuditStatus(auditStatus.toUpperCase());
        updateById(entity);
        marketingContentSearchService.indexContent(entity);
        return marketingContentConverter.toDetailResponse(entity);
    }

    @Override
    public List<MarketingContentDetailResponse> searchContents(MarketingContentSearchRequest request) {
        Long ownerBrokerId = VerifiedDomainAuthorization.requireBrokerScope(
                "marketing.content.read.own", "marketing.content.read.all");
        return marketingContentSearchService.search(request, ownerBrokerId).stream()
                .map(marketingContentConverter::toDetailResponse)
                .toList();
    }

    private void validateUpsertRequest(MarketingContentUpsertRequest request) {
        if (request.listingId() == null) {
            throw new IllegalArgumentException("房源ID不能为空");
        }
        if (!StringUtils.hasText(request.platform())) {
            throw new IllegalArgumentException("平台不能为空");
        }
        if (!MarketingContentTypeEnum.contains(request.contentType())) {
            throw new IllegalArgumentException("内容类型不合法: " + request.contentType());
        }
        if (StringUtils.hasText(request.auditStatus())
                && (!MarketingAuditStatusEnum.contains(request.auditStatus())
                || !MarketingAuditStatusEnum.DRAFT.name().equalsIgnoreCase(request.auditStatus()))) {
            throw new IllegalArgumentException("新建内容只能进入 DRAFT 审核状态");
        }
    }

    private void applyUpsert(MarketingContentEntity entity, MarketingContentUpsertRequest request) {
        entity.setListingId(request.listingId());
        entity.setPlatform(request.platform());
        entity.setTitle(request.title());
        entity.setContentType(request.contentType().toUpperCase());
        entity.setCopywriting(request.copywriting());
        entity.setAssetUrls(marketingContentConverter.joinValues(request.assetUrls()));
        entity.setCoverImageUrl(request.coverImageUrl());
        entity.setVideoUrl(request.videoUrl());
        entity.setPlatformVariant(StringUtils.hasText(request.platformVariant()) ? request.platformVariant() : "DEFAULT");
        entity.setTags(marketingContentConverter.joinValues(request.tags()));
        entity.setAuditStatus(MarketingAuditStatusEnum.DRAFT.name());
        entity.setParentContentId(request.parentContentId());
        entity.setSource(StringUtils.hasText(request.source()) ? request.source() : "manual");
    }

    private MarketingContentEntity requireContent(Long contentId, String ownOperation, String businessWideOperation) {
        Long ownerBrokerId = VerifiedDomainAuthorization.requireBrokerScope(ownOperation, businessWideOperation);
        return requireContent(contentId, ownOperation, businessWideOperation, ownerBrokerId);
    }

    private MarketingContentEntity requireContent(Long contentId, String ownOperation,
                                                  String businessWideOperation, Long ownerBrokerId) {
        MarketingContentEntity entity = ownerBrokerId == null ? getById(contentId)
                : getOne(new LambdaQueryWrapper<MarketingContentEntity>()
                        .eq(MarketingContentEntity::getId, contentId)
                        .eq(MarketingContentEntity::getOwnerBrokerId, ownerBrokerId));
        if (entity == null) {
            throw new IllegalArgumentException("营销内容不存在: " + contentId);
        }
        return entity;
    }
}
