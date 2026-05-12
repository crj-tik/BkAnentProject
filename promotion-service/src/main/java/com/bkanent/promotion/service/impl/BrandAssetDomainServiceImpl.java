package com.bkanent.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.promotion.entity.BrandAssetEntity;
import com.bkanent.promotion.mapper.BrandAssetMapper;
import com.bkanent.promotion.service.BrandAssetDomainService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 品牌素材领域服务实现。
 */
@Service
public class BrandAssetDomainServiceImpl extends ServiceImpl<BrandAssetMapper, BrandAssetEntity> implements BrandAssetDomainService {

    @Override
    public List<BrandAssetEntity> searchAssets(String assetType, String keyword) {
        LambdaQueryWrapper<BrandAssetEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(StringUtils.hasText(assetType), BrandAssetEntity::getAssetType, assetType);
        if (StringUtils.hasText(keyword)) {
            queryWrapper.and(wrapper -> wrapper.like(BrandAssetEntity::getAssetName, keyword)
                    .or()
                    .like(BrandAssetEntity::getTagNames, keyword));
        }
        queryWrapper.orderByDesc(BrandAssetEntity::getUpdatedAt);
        return list(queryWrapper);
    }
}
