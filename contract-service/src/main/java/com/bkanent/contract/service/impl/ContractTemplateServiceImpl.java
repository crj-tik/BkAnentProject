package com.bkanent.contract.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.contract.entity.ContractTemplateEntity;
import com.bkanent.contract.mapper.ContractTemplateMapper;
import com.bkanent.contract.service.ContractTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 合同模板领域服务实现。
 */
@Service
public class ContractTemplateServiceImpl extends ServiceImpl<ContractTemplateMapper, ContractTemplateEntity>
        implements ContractTemplateService {

    @Override
    public List<ContractTemplateEntity> listByContractType(String contractType) {
        return list(new LambdaQueryWrapper<ContractTemplateEntity>()
                .eq(StringUtils.hasText(contractType), ContractTemplateEntity::getContractType, contractType)
                .orderByAsc(ContractTemplateEntity::getTemplateCode)
                .orderByDesc(ContractTemplateEntity::getVersionNo));
    }
}
