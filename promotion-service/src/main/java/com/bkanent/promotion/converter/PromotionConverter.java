package com.bkanent.promotion.converter;

import com.bkanent.promotion.entity.BrandAssetEntity;
import com.bkanent.promotion.entity.PromotionEffectStatEntity;
import com.bkanent.promotion.entity.PromotionPublishRecordEntity;
import com.bkanent.promotion.model.BrandAssetResponse;
import com.bkanent.promotion.model.PromotionEffectResponse;
import com.bkanent.promotion.model.PromotionPublishResultResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 宣传模块对象转换器。
 */
@Component
public class PromotionConverter {

    public PromotionPublishResultResponse toPublishResult(PromotionPublishRecordEntity entity) {
        return new PromotionPublishResultResponse(
                entity.getId(),
                entity.getContentId(),
                entity.getPlatform(),
                entity.getPublishStatus(),
                entity.getExternalPublishId(),
                entity.getPublishMessage(),
                entity.getPublishTime()
        );
    }

    public PromotionEffectResponse toEffectResponse(PromotionEffectStatEntity entity) {
        return new PromotionEffectResponse(
                entity.getPublishRecordId(),
                entity.getContentId(),
                entity.getListingId(),
                entity.getPlatform(),
                entity.getExposureCount(),
                entity.getClickCount(),
                entity.getPrivateMessageCount(),
                entity.getLeadCount(),
                entity.getCtrValue(),
                entity.getConversionRate(),
                entity.getRoiValue(),
                entity.getStatDate()
        );
    }

    public BrandAssetResponse toBrandAssetResponse(BrandAssetEntity entity) {
        return new BrandAssetResponse(
                entity.getId(),
                entity.getAssetType(),
                entity.getAssetName(),
                entity.getAssetUrl(),
                entity.getPlatformScope(),
                splitTags(entity.getTagNames()),
                entity.getStatus(),
                entity.getRemark()
        );
    }

    public String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return tags.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.joining(","));
    }

    private List<String> splitTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
