package com.bkanent.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.promotion.entity.PromotionPublishRecordEntity;

import java.util.List;

/**
 * 宣传发布记录领域服务接口。
 */
public interface PromotionPublishRecordDomainService extends IService<PromotionPublishRecordEntity> {

    /**
     * 业务方法：listByListingId。
     */
    List<PromotionPublishRecordEntity> listByListingId(Long listingId);
}
