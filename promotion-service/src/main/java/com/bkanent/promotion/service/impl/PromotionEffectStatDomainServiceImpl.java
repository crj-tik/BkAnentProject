package com.bkanent.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.promotion.entity.PromotionEffectStatEntity;
import com.bkanent.promotion.mapper.PromotionEffectStatMapper;
import com.bkanent.promotion.service.PromotionEffectStatDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 宣传效果统计领域服务实现。
 */
@Service
public class PromotionEffectStatDomainServiceImpl
        extends ServiceImpl<PromotionEffectStatMapper, PromotionEffectStatEntity>
        implements PromotionEffectStatDomainService {

    @Override
    public List<PromotionEffectStatEntity> listByListingId(Long listingId) {
        return list(new LambdaQueryWrapper<PromotionEffectStatEntity>()
                .eq(PromotionEffectStatEntity::getListingId, listingId)
                .orderByDesc(PromotionEffectStatEntity::getStatDate)
                .orderByDesc(PromotionEffectStatEntity::getUpdatedAt));
    }
}
