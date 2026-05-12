package com.bkanent.settlement.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.settlement.entity.SettlementPayoutBatchEntity;
import com.bkanent.settlement.mapper.SettlementPayoutBatchMapper;
import com.bkanent.settlement.service.SettlementPayoutBatchService;
import org.springframework.stereotype.Service;

/**
 * 发放批次领域服务实现。
 */
@Service
public class SettlementPayoutBatchServiceImpl extends ServiceImpl<SettlementPayoutBatchMapper, SettlementPayoutBatchEntity>
        implements SettlementPayoutBatchService {
}
