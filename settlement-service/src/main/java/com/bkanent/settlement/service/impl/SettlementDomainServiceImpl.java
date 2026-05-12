package com.bkanent.settlement.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.settlement.entity.SettlementRecordEntity;
import com.bkanent.settlement.mapper.SettlementRecordMapper;
import com.bkanent.settlement.service.SettlementDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 结算主记录领域服务实现。
 */
@Service
public class SettlementDomainServiceImpl extends ServiceImpl<SettlementRecordMapper, SettlementRecordEntity>
        implements SettlementDomainService {

    @Override
    public SettlementRecordEntity findByEmployeeAndMonth(Long employeeId, String month) {
        return getOne(new LambdaQueryWrapper<SettlementRecordEntity>()
                .eq(SettlementRecordEntity::getEmployeeId, employeeId)
                .eq(SettlementRecordEntity::getStatMonth, month)
                .orderByDesc(SettlementRecordEntity::getId)
                .last("limit 1"));
    }

    @Override
    public List<SettlementRecordEntity> listByMonth(String month) {
        return list(new LambdaQueryWrapper<SettlementRecordEntity>()
                .eq(SettlementRecordEntity::getStatMonth, month)
                .orderByDesc(SettlementRecordEntity::getCommissionAmount)
                .orderByDesc(SettlementRecordEntity::getId));
    }
}
