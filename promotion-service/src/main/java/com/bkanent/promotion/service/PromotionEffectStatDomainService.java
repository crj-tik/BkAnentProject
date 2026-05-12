package com.bkanent.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.promotion.entity.PromotionEffectStatEntity;

import java.util.List;

/**
 * 宣传效果统计领域服务接口。
 */
public interface PromotionEffectStatDomainService extends IService<PromotionEffectStatEntity> {

    /**
     * 业务方法：listByListingId。
     */
    List<PromotionEffectStatEntity> listByListingId(Long listingId);
}
