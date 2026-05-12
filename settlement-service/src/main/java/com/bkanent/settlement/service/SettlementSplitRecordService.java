package com.bkanent.settlement.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.settlement.entity.SettlementSplitRecordEntity;

import java.util.List;

/**
 * 分佣明细领域服务接口。
 */
public interface SettlementSplitRecordService extends IService<SettlementSplitRecordEntity> {

    /**
     * 业务方法：listBySettlementId。
     */
    List<SettlementSplitRecordEntity> listBySettlementId(Long settlementId);
}
