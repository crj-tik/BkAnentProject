package com.bkanent.settlement.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.settlement.entity.SettlementRuleEntity;
import com.bkanent.settlement.mapper.SettlementRuleMapper;
import com.bkanent.settlement.service.SettlementRuleService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * 佣金规则领域服务实现。
 */
@Service
public class SettlementRuleServiceImpl extends ServiceImpl<SettlementRuleMapper, SettlementRuleEntity>
        implements SettlementRuleService {

    @Override
    public SettlementRuleEntity matchRule(String ruleCode, String contractType, BigDecimal dealAmount) {
        if (StringUtils.hasText(ruleCode)) {
            SettlementRuleEntity directRule = getOne(new LambdaQueryWrapper<SettlementRuleEntity>()
                    .eq(SettlementRuleEntity::getRuleCode, ruleCode)
                    .eq(SettlementRuleEntity::getStatus, "ACTIVE")
                    .last("limit 1"));
            if (directRule != null) {
                return directRule;
            }
        }
        List<SettlementRuleEntity> rules = list(new LambdaQueryWrapper<SettlementRuleEntity>()
                .eq(StringUtils.hasText(contractType), SettlementRuleEntity::getContractType, contractType)
                .eq(SettlementRuleEntity::getStatus, "ACTIVE")
                .orderByAsc(SettlementRuleEntity::getMinDealAmount));
        return rules.stream()
                .filter(rule -> matchDealAmount(rule, dealAmount))
                .findFirst()
                .orElse(null);
    }

    private boolean matchDealAmount(SettlementRuleEntity rule, BigDecimal dealAmount) {
        BigDecimal actualDealAmount = dealAmount == null ? BigDecimal.ZERO : dealAmount;
        boolean greaterThanMin = rule.getMinDealAmount() == null || actualDealAmount.compareTo(rule.getMinDealAmount()) >= 0;
        boolean lessThanMax = rule.getMaxDealAmount() == null || actualDealAmount.compareTo(rule.getMaxDealAmount()) <= 0;
        return greaterThanMin && lessThanMax;
    }
}
