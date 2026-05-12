package com.bkanent.settlement.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.settlement.entity.SettlementMonthlySummaryEntity;

import java.util.List;

/**
 * 月度提成汇总领域服务接口。
 */
public interface SettlementMonthlySummaryService extends IService<SettlementMonthlySummaryEntity> {

    /**
     * 业务方法：listByMonth。
     */
    List<SettlementMonthlySummaryEntity> listByMonth(String month, String summaryScope);
}
