package com.bkanent.settlement.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.settlement.entity.SettlementPaymentRecordEntity;

import java.util.List;

/**
 * 打款流水领域服务接口。
 */
public interface SettlementPaymentRecordService extends IService<SettlementPaymentRecordEntity> {

    /**
     * 业务方法：listByBatchId。
     */
    List<SettlementPaymentRecordEntity> listByBatchId(Long batchId);
}
