package com.bkanent.customer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.customer.entity.CustomerFollowRecordEntity;
import com.bkanent.customer.mapper.CustomerFollowRecordMapper;
import com.bkanent.customer.service.CustomerFollowRecordDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 客户跟进记录领域服务实现。
 */
@Service
public class CustomerFollowRecordDomainServiceImpl
        extends ServiceImpl<CustomerFollowRecordMapper, CustomerFollowRecordEntity>
        implements CustomerFollowRecordDomainService {

    @Override
    public List<CustomerFollowRecordEntity> listByCustomerId(Long customerId) {
        return list(new LambdaQueryWrapper<CustomerFollowRecordEntity>()
                .eq(CustomerFollowRecordEntity::getCustomerId, customerId)
                .orderByDesc(CustomerFollowRecordEntity::getCreatedAt));
    }
}
