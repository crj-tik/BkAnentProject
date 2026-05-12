package com.bkanent.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.promotion.entity.PromotionPublishRecordEntity;
import com.bkanent.promotion.mapper.PromotionPublishRecordMapper;
import com.bkanent.promotion.service.PromotionPublishRecordDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 宣传发布记录领域服务实现。
 */
@Service
public class PromotionPublishRecordDomainServiceImpl
        extends ServiceImpl<PromotionPublishRecordMapper, PromotionPublishRecordEntity>
        implements PromotionPublishRecordDomainService {

    @Override
    public List<PromotionPublishRecordEntity> listByListingId(Long listingId) {
        return list(new LambdaQueryWrapper<PromotionPublishRecordEntity>()
                .eq(PromotionPublishRecordEntity::getListingId, listingId)
                .orderByDesc(PromotionPublishRecordEntity::getPublishTime)
                .orderByDesc(PromotionPublishRecordEntity::getCreatedAt));
    }
}
