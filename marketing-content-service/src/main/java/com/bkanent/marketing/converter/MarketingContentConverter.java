package com.bkanent.marketing.converter;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.marketing.entity.MarketingContentEntity;
import com.bkanent.marketing.model.MarketingContentDetailResponse;
import com.bkanent.marketing.search.MarketingContentDocument;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 营销内容对象转换器。
 */
@Component
public class MarketingContentConverter {

    public MarketingContentEntity toEntity(MarketingContentDTO dto) {
        MarketingContentEntity entity = new MarketingContentEntity();
        entity.setListingId(dto.listingId());
        entity.setPlatform(dto.platform());
        entity.setTitle(dto.title());
        entity.setContentType(dto.contentType());
        entity.setCopywriting(dto.copywriting());
        entity.setAssetUrls(joinValues(dto.assetUrls()));
        entity.setCoverImageUrl(dto.coverImageUrl());
        entity.setVideoUrl(dto.videoUrl());
        entity.setVersionNo(dto.versionNo());
        entity.setPlatformVariant(dto.platformVariant());
        entity.setTags(joinValues(dto.tags()));
        entity.setAuditStatus(dto.auditStatus());
        entity.setStatus(dto.status());
        return entity;
    }

    public MarketingContentDTO toDto(MarketingContentEntity entity) {
        return new MarketingContentDTO(
                entity.getId(),
                entity.getListingId(),
                entity.getPlatform(),
                entity.getTitle(),
                entity.getContentType(),
                entity.getCopywriting(),
                splitValues(entity.getAssetUrls()),
                entity.getCoverImageUrl(),
                entity.getVideoUrl(),
                entity.getVersionNo(),
                entity.getPlatformVariant(),
                splitValues(entity.getTags()),
                entity.getAuditStatus(),
                entity.getStatus()
        );
    }

    public MarketingContentDetailResponse toDetailResponse(MarketingContentEntity entity) {
        return new MarketingContentDetailResponse(
                entity.getId(),
                entity.getListingId(),
                entity.getPlatform(),
                entity.getTitle(),
                entity.getContentType(),
                entity.getCopywriting(),
                splitValues(entity.getAssetUrls()),
                entity.getCoverImageUrl(),
                entity.getVideoUrl(),
                entity.getVersionNo(),
                entity.getParentContentId(),
                entity.getPlatformVariant(),
                splitValues(entity.getTags()),
                entity.getAuditStatus(),
                entity.getStatus(),
                entity.getPublishMessage(),
                entity.getExternalPublishId(),
                entity.getPublishTime()
        );
    }

    public MarketingContentDocument toDocument(MarketingContentEntity entity) {
        return new MarketingContentDocument(
                entity.getId(),
                entity.getListingId(),
                entity.getPlatform(),
                entity.getTitle(),
                entity.getContentType(),
                entity.getCopywriting(),
                splitValues(entity.getTags()),
                entity.getStatus(),
                entity.getAuditStatus(),
                entity.getPlatformVariant()
        );
    }

    public String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.joining(","));
    }

    public List<String> splitValues(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
