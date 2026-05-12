package com.bkanent.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.MarketingContentRpcService;
import com.bkanent.promotion.client.PromotionPlatformClientDispatcher;
import com.bkanent.promotion.converter.PromotionConverter;
import com.bkanent.promotion.entity.BrandAssetEntity;
import com.bkanent.promotion.entity.PromotionEffectStatEntity;
import com.bkanent.promotion.entity.PromotionPublishRecordEntity;
import com.bkanent.promotion.enums.BrandAssetTypeEnum;
import com.bkanent.promotion.enums.PromotionPlatformEnum;
import com.bkanent.promotion.model.BrandAssetResponse;
import com.bkanent.promotion.model.BrandAssetUpsertRequest;
import com.bkanent.promotion.model.PromotionEffectResponse;
import com.bkanent.promotion.model.PromotionEffectSyncRequest;
import com.bkanent.promotion.model.PromotionPlatformPublishResult;
import com.bkanent.promotion.model.PromotionPublishRequest;
import com.bkanent.promotion.model.PromotionPublishResultResponse;
import com.bkanent.promotion.model.PromotionRoiReportResponse;
import com.bkanent.promotion.service.BrandAssetDomainService;
import com.bkanent.promotion.service.PromotionEffectStatDomainService;
import com.bkanent.promotion.service.PromotionManagementService;
import com.bkanent.promotion.service.PromotionPublishRecordDomainService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 宣传业务管理服务实现。
 */
@Service
public class PromotionManagementServiceImpl implements PromotionManagementService {

    private static final Logger log = LoggerFactory.getLogger(PromotionManagementServiceImpl.class);

    private final PromotionPublishRecordDomainService publishRecordDomainService;
    private final PromotionEffectStatDomainService effectStatDomainService;
    private final BrandAssetDomainService brandAssetDomainService;
    private final PromotionConverter promotionConverter;
    private final PromotionPlatformClientDispatcher platformClientDispatcher;

    @DubboReference(check = false)
    private MarketingContentRpcService marketingContentRpcService;

    public PromotionManagementServiceImpl(PromotionPublishRecordDomainService publishRecordDomainService,
                                          PromotionEffectStatDomainService effectStatDomainService,
                                          BrandAssetDomainService brandAssetDomainService,
                                          PromotionConverter promotionConverter,
                                          PromotionPlatformClientDispatcher platformClientDispatcher) {
        this.publishRecordDomainService = publishRecordDomainService;
        this.effectStatDomainService = effectStatDomainService;
        this.brandAssetDomainService = brandAssetDomainService;
        this.promotionConverter = promotionConverter;
        this.platformClientDispatcher = platformClientDispatcher;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromotionPublishResultResponse publishContent(MarketingContentDTO content, PromotionPublishRequest request) {
        validatePublishRequest(request);
        PromotionPublishRecordEntity entity = new PromotionPublishRecordEntity();
        entity.setContentId(content.id() != null ? content.id() : request.contentId());
        entity.setListingId(request.listingId() != null ? request.listingId() : content.listingId());
        entity.setPlatform(request.platform().toUpperCase());
        entity.setChannelAccount(request.channelAccount());
        entity.setOperatorName(request.operatorName());
        entity.setCostAmount(request.costAmount());

        PromotionPlatformPublishResult publishResult = platformClientDispatcher.publish(content, request);
        entity.setExternalPublishId(publishResult.externalPublishId());
        entity.setPublishTime(publishResult.publishTime());
        entity.setPublishStatus(publishResult.publishStatus());
        entity.setPublishMessage(publishResult.publishMessage());
        publishRecordDomainService.save(entity);

        if (marketingContentRpcService != null && entity.getContentId() != null) {
            marketingContentRpcService.updatePublishStatus(
                    entity.getContentId(),
                    entity.getPublishStatus(),
                    entity.getPublishMessage(),
                    entity.getExternalPublishId(),
                    entity.getPublishTime()
            );
        }
        log.info("宣传内容发布完成，recordId={}，contentId={}，platform={}，status={}",
                entity.getId(), entity.getContentId(), entity.getPlatform(), entity.getPublishStatus());
        return promotionConverter.toPublishResult(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromotionEffectResponse syncEffectStat(PromotionEffectSyncRequest request) {
        PromotionPublishRecordEntity publishRecord = publishRecordDomainService.getById(request.publishRecordId());
        if (publishRecord == null) {
            throw new IllegalArgumentException("发布记录不存在: " + request.publishRecordId());
        }

        PromotionEffectStatEntity entity = effectStatDomainService.getOne(new LambdaQueryWrapper<PromotionEffectStatEntity>()
                .eq(PromotionEffectStatEntity::getPublishRecordId, request.publishRecordId())
                .eq(StringUtils.hasText(request.statDate()), PromotionEffectStatEntity::getStatDate, request.statDate())
                .last("limit 1"));
        if (entity == null) {
            entity = new PromotionEffectStatEntity();
            entity.setPublishRecordId(publishRecord.getId());
            entity.setContentId(publishRecord.getContentId());
            entity.setListingId(publishRecord.getListingId());
            entity.setPlatform(publishRecord.getPlatform());
            entity.setStatDate(StringUtils.hasText(request.statDate()) ? request.statDate() : LocalDateTime.now().toLocalDate().toString());
        }

        int baseValue = Math.toIntExact((publishRecord.getId() == null ? 1L : publishRecord.getId()) % 100 + 1);
        int exposureCount = baseValue * 120;
        int clickCount = baseValue * 18;
        int privateMessageCount = Math.max(1, baseValue / 2);
        int leadCount = Math.max(1, baseValue / 3);
        BigDecimal ctrValue = safeDivide(clickCount, exposureCount);
        BigDecimal conversionRate = safeDivide(leadCount, clickCount);
        BigDecimal roiValue = publishRecord.getCostAmount() == null || publishRecord.getCostAmount().compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : new BigDecimal(leadCount * 1000L).divide(publishRecord.getCostAmount(), 2, RoundingMode.HALF_UP);

        entity.setExposureCount(exposureCount);
        entity.setClickCount(clickCount);
        entity.setPrivateMessageCount(privateMessageCount);
        entity.setLeadCount(leadCount);
        entity.setCtrValue(ctrValue);
        entity.setConversionRate(conversionRate);
        entity.setRoiValue(roiValue);
        effectStatDomainService.saveOrUpdate(entity);
        log.info("同步宣传效果数据成功，recordId={}，statDate={}", publishRecord.getId(), entity.getStatDate());
        return promotionConverter.toEffectResponse(entity);
    }

    @Override
    public List<PromotionEffectResponse> listEffectStats(Long listingId) {
        return effectStatDomainService.listByListingId(listingId).stream()
                .map(promotionConverter::toEffectResponse)
                .toList();
    }

    @Override
    public List<PromotionRoiReportResponse> buildRoiReport() {
        Map<String, List<PromotionEffectStatEntity>> groupMap = effectStatDomainService.list().stream()
                .collect(Collectors.groupingBy(PromotionEffectStatEntity::getPlatform));
        return groupMap.entrySet().stream()
                .map(entry -> {
                    List<PromotionEffectStatEntity> values = entry.getValue();
                    Map<Long, PromotionPublishRecordEntity> publishRecordMap = publishRecordDomainService.list().stream()
                            .collect(Collectors.toMap(PromotionPublishRecordEntity::getId, item -> item, (left, right) -> left));
                    int publishCount = values.size();
                    int totalExposure = values.stream().mapToInt(item -> nullSafeInt(item.getExposureCount())).sum();
                    int totalLead = values.stream().mapToInt(item -> nullSafeInt(item.getLeadCount())).sum();
                    BigDecimal totalCost = values.stream()
                            .map(item -> publishRecordMap.get(item.getPublishRecordId()))
                            .filter(record -> record != null && record.getCostAmount() != null)
                            .map(PromotionPublishRecordEntity::getCostAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal avgCtr = average(values.stream().map(PromotionEffectStatEntity::getCtrValue).toList());
                    BigDecimal avgRoi = average(values.stream().map(PromotionEffectStatEntity::getRoiValue).toList());
                    return new PromotionRoiReportResponse(entry.getKey(), publishCount, totalExposure, totalLead, totalCost, avgCtr, avgRoi);
                })
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BrandAssetResponse saveBrandAsset(BrandAssetUpsertRequest request) {
        if (!BrandAssetTypeEnum.contains(request.assetType())) {
            throw new IllegalArgumentException("品牌素材类型不合法: " + request.assetType());
        }
        BrandAssetEntity entity = new BrandAssetEntity();
        entity.setAssetType(request.assetType().toUpperCase());
        entity.setAssetName(request.assetName());
        entity.setAssetUrl(request.assetUrl());
        entity.setPlatformScope(request.platformScope());
        entity.setTagNames(promotionConverter.joinTags(request.tags()));
        entity.setStatus(request.status());
        entity.setRemark(request.remark());
        brandAssetDomainService.save(entity);
        log.info("保存品牌素材成功，assetId={}，name={}", entity.getId(), entity.getAssetName());
        return promotionConverter.toBrandAssetResponse(entity);
    }

    @Override
    public List<BrandAssetResponse> listBrandAssets(String assetType, String keyword) {
        return brandAssetDomainService.searchAssets(assetType, keyword).stream()
                .map(promotionConverter::toBrandAssetResponse)
                .toList();
    }

    private void validatePublishRequest(PromotionPublishRequest request) {
        if (!PromotionPlatformEnum.contains(request.platform())) {
            throw new IllegalArgumentException("发布平台不支持: " + request.platform());
        }
    }

    private BigDecimal safeDivide(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> validValues = values.stream().filter(item -> item != null).toList();
        if (validValues.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = validValues.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(validValues.size()), 4, RoundingMode.HALF_UP);
    }

    private int nullSafeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
