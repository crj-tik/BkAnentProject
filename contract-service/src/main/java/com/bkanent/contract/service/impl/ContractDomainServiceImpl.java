package com.bkanent.contract.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.contract.entity.ContractEntity;
import com.bkanent.contract.mapper.ContractMapper;
import com.bkanent.contract.service.ContractDomainService;
import org.springframework.stereotype.Service;

@Service
public class ContractDomainServiceImpl extends ServiceImpl<ContractMapper, ContractEntity> implements ContractDomainService {
}
