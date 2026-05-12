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
        List<MarketingContentEntity> entities = contents.stream()
                .map(marketingContentConverter::toEntity)
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
        return list(new LambdaQueryWrapper<MarketingContentEntity>()
                .eq(MarketingContentEntity::getListingId, listingId)
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
        MarketingContentEntity entity = requireContent(contentId);
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
        validateUpsertRequest(request);
        MarketingContentEntity entity = new MarketingContentEntity();
        applyUpsert(entity, request);
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
        MarketingContentEntity sourceEntity = requireContent(sourceContentId);
        validateUpsertRequest(request);
        MarketingContentEntity entity = new MarketingContentEntity();
        applyUpsert(entity, request);
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
        if (!MarketingPublishStatusEnum.contains(request.publishStatus())) {
            throw new IllegalArgumentException("发布状态不合法: " + request.publishStatus());
        }
        MarketingContentEntity entity = requireContent(contentId);
        entity.setStatus(request.publishStatus().toUpperCase());
        entity.setPublishMessage(request.publishMessage());
        entity.setExternalPublishId(request.externalPublishId());
        entity.setPublishTime(request.publishTime());
        updateById(entity);
        marketingContentSearchService.indexContent(entity);
        log.info("更新营销内容发布状态成功，contentId={}，status={}", contentId, entity.getStatus());
        return marketingContentConverter.toDetailResponse(entity);
    }

    @Override
    public List<MarketingContentDetailResponse> searchContents(MarketingContentSearchRequest request) {
        return marketingContentSearchService.search(request).stream()
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
        if (StringUtils.hasText(request.auditStatus()) && !MarketingAuditStatusEnum.contains(request.auditStatus())) {
            throw new IllegalArgumentException("审核状态不合法: " + request.auditStatus());
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
        entity.setAuditStatus(StringUtils.hasText(request.auditStatus()) ? request.auditStatus().toUpperCase() : MarketingAuditStatusEnum.DRAFT.name());
        entity.setParentContentId(request.parentContentId());
    }

    private MarketingContentEntity requireContent(Long contentId) {
        MarketingContentEntity entity = getById(contentId);
        if (entity == null) {
            throw new IllegalArgumentException("营销内容不存在: " + contentId);
        }
        return entity;
    }
}
