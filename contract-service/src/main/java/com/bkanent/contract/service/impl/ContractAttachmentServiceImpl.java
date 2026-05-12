package com.bkanent.contract.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.contract.entity.ContractAttachmentEntity;
import com.bkanent.contract.mapper.ContractAttachmentMapper;
import com.bkanent.contract.service.ContractAttachmentService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 合同附件领域服务实现。
 */
@Service
public class ContractAttachmentServiceImpl extends ServiceImpl<ContractAttachmentMapper, ContractAttachmentEntity>
        implements ContractAttachmentService {

    @Override
    public List<ContractAttachmentEntity> listByContractId(Long contractId) {
        return list(new LambdaQueryWrapper<ContractAttachmentEntity>()
                .eq(ContractAttachmentEntity::getContractId, contractId)
                .orderByAsc(ContractAttachmentEntity::getId));
    }
}
