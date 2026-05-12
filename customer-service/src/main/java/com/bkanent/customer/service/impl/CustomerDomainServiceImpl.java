package com.bkanent.customer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.mapper.CustomerMapper;
import com.bkanent.customer.model.CustomerQueryRequest;
import com.bkanent.customer.service.CustomerDomainService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 客户与业主档案领域服务实现。
 */
@Service
public class CustomerDomainServiceImpl extends ServiceImpl<CustomerMapper, CustomerEntity> implements CustomerDomainService {

    @Override
    public List<CustomerEntity> searchProfiles(CustomerQueryRequest request) {
        LambdaQueryWrapper<CustomerEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(StringUtils.hasText(request.profileType()), CustomerEntity::getProfileType, request.profileType());
        queryWrapper.eq(request.brokerId() != null, CustomerEntity::getBrokerId, request.brokerId());
        queryWrapper.eq(StringUtils.hasText(request.intention()), CustomerEntity::getIntention, request.intention());
        queryWrapper.ge(request.budgetMin() != null, CustomerEntity::getBudgetMax, request.budgetMin());
        queryWrapper.le(request.budgetMax() != null, CustomerEntity::getBudgetMin, request.budgetMax());
        if (StringUtils.hasText(request.keyword())) {
            queryWrapper.and(wrapper -> wrapper.like(CustomerEntity::getName, request.keyword())
                    .or()
                    .like(CustomerEntity::getMobile, request.keyword())
                    .or()
                    .like(CustomerEntity::getPreferredArea, request.keyword()));
        }
        queryWrapper.orderByDesc(CustomerEntity::getUpdatedAt);
        return list(queryWrapper);
    }
}
