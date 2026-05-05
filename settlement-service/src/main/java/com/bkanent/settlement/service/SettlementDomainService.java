package com.bkanent.settlement.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.settlement.entity.SettlementRecordEntity;

public interface SettlementDomainService extends IService<SettlementRecordEntity> {

    SettlementRecordEntity findByEmployeeAndMonth(Long employeeId, String month);
}
