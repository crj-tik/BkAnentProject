package com.bkanent.customer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.customer.entity.OwnerEntrustRecordEntity;

import java.util.List;

/**
 * 业主委托记录领域服务接口。
 */
public interface OwnerEntrustRecordDomainService extends IService<OwnerEntrustRecordEntity> {

    /**
     * 业务方法：listByCustomerId。
     */
    List<OwnerEntrustRecordEntity> listByCustomerId(Long customerId);

    /**
     * 业务方法：listExpiringWithinDays。
     */
    List<OwnerEntrustRecordEntity> listExpiringWithinDays(int days);
}
