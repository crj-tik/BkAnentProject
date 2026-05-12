package com.bkanent.settlement.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.settlement.entity.SettlementRecordEntity;

import java.util.List;

/**
 * 结算主记录领域服务接口。
 */
public interface SettlementDomainService extends IService<SettlementRecordEntity> {

    /**
     * 业务方法：findByEmployeeAndMonth。
     */
    SettlementRecordEntity findByEmployeeAndMonth(Long employeeId, String month);

    /**
     * 业务方法：listByMonth。
     */
    List<SettlementRecordEntity> listByMonth(String month);
}
