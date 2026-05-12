package com.bkanent.customer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.model.CustomerQueryRequest;

import java.util.List;

/**
 * 客户与业主档案领域服务接口。
 */
public interface CustomerDomainService extends IService<CustomerEntity> {

    /**
     * 业务方法：searchProfiles。
     */
    List<CustomerEntity> searchProfiles(CustomerQueryRequest request);
}
