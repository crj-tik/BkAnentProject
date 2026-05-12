package com.bkanent.customer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.customer.entity.CustomerFavoriteListingEntity;
import com.bkanent.customer.mapper.CustomerFavoriteListingMapper;
import com.bkanent.customer.service.CustomerFavoriteListingDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 客户收藏房源领域服务实现。
 */
@Service
public class CustomerFavoriteListingDomainServiceImpl
        extends ServiceImpl<CustomerFavoriteListingMapper, CustomerFavoriteListingEntity>
        implements CustomerFavoriteListingDomainService {

    @Override
    public List<CustomerFavoriteListingEntity> listByCustomerId(Long customerId) {
        return list(new LambdaQueryWrapper<CustomerFavoriteListingEntity>()
                .eq(CustomerFavoriteListingEntity::getCustomerId, customerId)
                .orderByDesc(CustomerFavoriteListingEntity::getUpdatedAt));
    }
}
