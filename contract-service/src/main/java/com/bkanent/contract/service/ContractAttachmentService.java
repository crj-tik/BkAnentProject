package com.bkanent.contract.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.contract.entity.ContractAttachmentEntity;

import java.util.List;

/**
 * 合同附件领域服务接口。
 */
public interface ContractAttachmentService extends IService<ContractAttachmentEntity> {

    /**
     * 业务方法：listByContractId。
     */
    List<ContractAttachmentEntity> listByContractId(Long contractId);
}
