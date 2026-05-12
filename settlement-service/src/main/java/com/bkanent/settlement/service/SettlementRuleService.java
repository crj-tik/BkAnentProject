package com.bkanent.settlement.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.settlement.entity.SettlementRuleEntity;

import java.math.BigDecimal;

/**
 * 佣金规则领域服务接口。
 */
public interface SettlementRuleService extends IService<SettlementRuleEntity> {

    /**
     * 业务方法：matchRule。
     */
    SettlementRuleEntity matchRule(String ruleCode, String contractType, BigDecimal dealAmount);
}
