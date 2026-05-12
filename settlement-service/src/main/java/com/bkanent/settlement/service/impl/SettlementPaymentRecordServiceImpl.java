package com.bkanent.settlement.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.settlement.entity.SettlementPaymentRecordEntity;
import com.bkanent.settlement.mapper.SettlementPaymentRecordMapper;
import com.bkanent.settlement.service.SettlementPaymentRecordService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 打款流水领域服务实现。
 */
@Service
public class SettlementPaymentRecordServiceImpl extends ServiceImpl<SettlementPaymentRecordMapper, SettlementPaymentRecordEntity>
        implements SettlementPaymentRecordService {

    @Override
    public List<SettlementPaymentRecordEntity> listByBatchId(Long batchId) {
        return list(new LambdaQueryWrapper<SettlementPaymentRecordEntity>()
                .eq(SettlementPaymentRecordEntity::getBatchId, batchId)
                .orderByAsc(SettlementPaymentRecordEntity::getId));
    }
}
