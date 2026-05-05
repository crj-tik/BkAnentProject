package com.bkanent.settlement.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.settlement.entity.SettlementRecordEntity;
import com.bkanent.settlement.mapper.SettlementRecordMapper;
import com.bkanent.settlement.service.SettlementDomainService;
import org.springframework.stereotype.Service;

@Service
public class SettlementDomainServiceImpl extends ServiceImpl<SettlementRecordMapper, SettlementRecordEntity> implements SettlementDomainService {

    @Override
    public SettlementRecordEntity findByEmployeeAndMonth(Long employeeId, String month) {
        return getOne(new LambdaQueryWrapper<SettlementRecordEntity>()
                .eq(SettlementRecordEntity::getEmployeeId, employeeId)
                .eq(SettlementRecordEntity::getStatMonth, month)
                .last("limit 1"));
    }
}
