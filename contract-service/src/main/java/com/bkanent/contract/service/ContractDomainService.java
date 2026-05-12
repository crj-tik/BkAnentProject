package com.bkanent.contract.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.contract.entity.ContractEntity;

import java.util.List;

/**
 * 合同领域服务接口。
 */
public interface ContractDomainService extends IService<ContractEntity> {

    /**
     * 业务方法：listPendingSignContracts。
     */
    List<ContractEntity> listPendingSignContracts(int days);

    /**
     * 业务方法：listContracts。
     */
    List<ContractEntity> listContracts(String contractType, String status);
}
