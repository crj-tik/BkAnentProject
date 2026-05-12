package com.bkanent.customer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.customer.entity.CustomerFavoriteListingEntity;

import java.util.List;

/**
 * 客户收藏房源领域服务接口。
 */
public interface CustomerFavoriteListingDomainService extends IService<CustomerFavoriteListingEntity> {

    /**
     * 业务方法：listByCustomerId。
     */
    List<CustomerFavoriteListingEntity> listByCustomerId(Long customerId);
}
