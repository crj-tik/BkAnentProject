package com.bkanent.settlement.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.settlement.entity.SettlementMonthlySummaryEntity;
import com.bkanent.settlement.mapper.SettlementMonthlySummaryMapper;
import com.bkanent.settlement.service.SettlementMonthlySummaryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 月度提成汇总领域服务实现。
 */
@Service
public class SettlementMonthlySummaryServiceImpl extends ServiceImpl<SettlementMonthlySummaryMapper, SettlementMonthlySummaryEntity>
        implements SettlementMonthlySummaryService {

    @Override
    public List<SettlementMonthlySummaryEntity> listByMonth(String month, String summaryScope) {
        return list(new LambdaQueryWrapper<SettlementMonthlySummaryEntity>()
                .eq(SettlementMonthlySummaryEntity::getStatMonth, month)
                .eq(StringUtils.hasText(summaryScope), SettlementMonthlySummaryEntity::getSummaryScope, summaryScope)
                .orderByDesc(SettlementMonthlySummaryEntity::getTotalCommissionAmount)
                .orderByDesc(SettlementMonthlySummaryEntity::getId));
    }
}
