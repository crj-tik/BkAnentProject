package com.bkanent.settlement.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.settlement.entity.SettlementRuleTierEntity;

import java.math.BigDecimal;
import java.util.List;

/**
 * 阶梯佣金规则领域服务接口。
 */
public interface SettlementRuleTierService extends IService<SettlementRuleTierEntity> {

    /**
     * 业务方法：listByRuleId。
     */
    List<SettlementRuleTierEntity> listByRuleId(Long ruleId);

    /**
     * 业务方法：matchTier。
     */
    SettlementRuleTierEntity matchTier(Long ruleId, BigDecimal dealAmount);
}
