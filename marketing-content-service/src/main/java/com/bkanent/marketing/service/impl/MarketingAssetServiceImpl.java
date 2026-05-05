package com.bkanent.marketing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.marketing.entity.MarketingContentEntity;
import com.bkanent.marketing.mapper.MarketingContentMapper;
import com.bkanent.marketing.service.MarketingAssetService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MarketingAssetServiceImpl extends ServiceImpl<MarketingContentMapper, MarketingContentEntity> implements MarketingAssetService {

    @Override
    public void saveContents(List<MarketingContentDTO> contents) {
        List<MarketingContentEntity> entities = contents.stream().map(this::toEntity).toList();
        saveBatch(entities);
    }

    @Override
    public List<MarketingContentDTO> listByListingId(Long listingId) {
        return list(new LambdaQueryWrapper<MarketingContentEntity>()
                .eq(MarketingContentEntity::getListingId, listingId))
                .stream()
                .map(this::toDto)
                .toList();
    }

    private MarketingContentEntity toEntity(MarketingContentDTO dto) {
        MarketingContentEntity entity = new MarketingContentEntity();
        entity.setListingId(dto.listingId());
        entity.setPlatform(dto.platform());
        entity.setCopywriting(dto.copywriting());
        entity.setAssetUrls(String.join(",", dto.assetUrls()));
        entity.setStatus(dto.status());
        return entity;
    }

    private MarketingContentDTO toDto(MarketingContentEntity entity) {
        List<String> assetUrls = entity.getAssetUrls() == null || entity.getAssetUrls().isBlank()
                ? List.of()
                : Arrays.stream(entity.getAssetUrls().split(",")).map(String::trim).collect(Collectors.toList());
        return new MarketingContentDTO(entity.getListingId(), entity.getPlatform(), entity.getCopywriting(), assetUrls, entity.getStatus());
    }
}
