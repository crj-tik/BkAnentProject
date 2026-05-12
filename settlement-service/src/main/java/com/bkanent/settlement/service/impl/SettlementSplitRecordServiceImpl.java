package com.bkanent.settlement.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.settlement.entity.SettlementSplitRecordEntity;
import com.bkanent.settlement.mapper.SettlementSplitRecordMapper;
import com.bkanent.settlement.service.SettlementSplitRecordService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分佣明细领域服务实现。
 */
@Service
public class SettlementSplitRecordServiceImpl extends ServiceImpl<SettlementSplitRecordMapper, SettlementSplitRecordEntity>
        implements SettlementSplitRecordService {

    @Override
    public List<SettlementSplitRecordEntity> listBySettlementId(Long settlementId) {
        return list(new LambdaQueryWrapper<SettlementSplitRecordEntity>()
                .eq(SettlementSplitRecordEntity::getSettlementId, settlementId)
                .orderByAsc(SettlementSplitRecordEntity::getId));
    }
}
