package com.bkanent.settlement.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.settlement.entity.SettlementRuleTierEntity;
import com.bkanent.settlement.mapper.SettlementRuleTierMapper;
import com.bkanent.settlement.service.SettlementRuleTierService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 阶梯佣金规则领域服务实现。
 */
@Service
public class SettlementRuleTierServiceImpl extends ServiceImpl<SettlementRuleTierMapper, SettlementRuleTierEntity>
        implements SettlementRuleTierService {

    @Override
    public List<SettlementRuleTierEntity> listByRuleId(Long ruleId) {
        return list(new LambdaQueryWrapper<SettlementRuleTierEntity>()
                .eq(SettlementRuleTierEntity::getRuleId, ruleId)
                .orderByAsc(SettlementRuleTierEntity::getTierLevel));
    }

    @Override
    public SettlementRuleTierEntity matchTier(Long ruleId, BigDecimal dealAmount) {
        BigDecimal actualDealAmount = dealAmount == null ? BigDecimal.ZERO : dealAmount;
        return listByRuleId(ruleId).stream()
                .filter(tier -> (tier.getMinDealAmount() == null || actualDealAmount.compareTo(tier.getMinDealAmount()) >= 0)
                        && (tier.getMaxDealAmount() == null || actualDealAmount.compareTo(tier.getMaxDealAmount()) <= 0))
                .findFirst()
                .orElse(null);
    }
}
