package com.bkanent.contract.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.contract.entity.ContractTemplateEntity;

import java.util.List;

/**
 * 合同模板领域服务接口。
 */
public interface ContractTemplateService extends IService<ContractTemplateEntity> {

    /**
     * 业务方法：listByContractType。
     */
    List<ContractTemplateEntity> listByContractType(String contractType);
}
