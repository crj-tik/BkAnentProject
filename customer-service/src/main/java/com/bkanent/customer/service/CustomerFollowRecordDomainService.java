package com.bkanent.customer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.customer.entity.CustomerFollowRecordEntity;

import java.util.List;

/**
 * 客户跟进记录领域服务接口。
 */
public interface CustomerFollowRecordDomainService extends IService<CustomerFollowRecordEntity> {

    /**
     * 业务方法：listByCustomerId。
     */
    List<CustomerFollowRecordEntity> listByCustomerId(Long customerId);
}
