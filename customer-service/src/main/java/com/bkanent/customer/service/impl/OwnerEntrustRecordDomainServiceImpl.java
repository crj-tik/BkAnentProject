package com.bkanent.customer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.customer.entity.OwnerEntrustRecordEntity;
import com.bkanent.customer.enums.EntrustStatusEnum;
import com.bkanent.customer.mapper.OwnerEntrustRecordMapper;
import com.bkanent.customer.service.OwnerEntrustRecordDomainService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 业主委托记录领域服务实现。
 */
@Service
public class OwnerEntrustRecordDomainServiceImpl
        extends ServiceImpl<OwnerEntrustRecordMapper, OwnerEntrustRecordEntity>
        implements OwnerEntrustRecordDomainService {

    @Override
    public List<OwnerEntrustRecordEntity> listByCustomerId(Long customerId) {
        return list(new LambdaQueryWrapper<OwnerEntrustRecordEntity>()
                .eq(OwnerEntrustRecordEntity::getCustomerId, customerId)
                .orderByDesc(OwnerEntrustRecordEntity::getEntrustEndDate));
    }

    @Override
    public List<OwnerEntrustRecordEntity> listExpiringWithinDays(int days) {
        LocalDate today = LocalDate.now();
        LocalDate endDate = today.plusDays(days);
        return list(new LambdaQueryWrapper<OwnerEntrustRecordEntity>()
                .eq(OwnerEntrustRecordEntity::getStatus, EntrustStatusEnum.ACTIVE.name())
                .ge(OwnerEntrustRecordEntity::getEntrustEndDate, today)
                .le(OwnerEntrustRecordEntity::getEntrustEndDate, endDate)
                .orderByAsc(OwnerEntrustRecordEntity::getEntrustEndDate));
    }
}
