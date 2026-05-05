package com.bkanent.settlement.service;

import com.bkanent.common.rpc.SettlementRpcService;
import com.bkanent.settlement.entity.SettlementRecordEntity;
import org.apache.dubbo.config.annotation.DubboService;

import java.math.BigDecimal;

@DubboService
public class SettlementRpcServiceImpl implements SettlementRpcService {

    private final SettlementDomainService settlementDomainService;

    public SettlementRpcServiceImpl(SettlementDomainService settlementDomainService) {
        this.settlementDomainService = settlementDomainService;
    }

    @Override
    public BigDecimal queryMonthlyCommission(Long employeeId, String month) {
        SettlementRecordEntity entity = settlementDomainService.findByEmployeeAndMonth(employeeId, month);
        return entity == null ? BigDecimal.ZERO : entity.getCommissionAmount();
    }
}
