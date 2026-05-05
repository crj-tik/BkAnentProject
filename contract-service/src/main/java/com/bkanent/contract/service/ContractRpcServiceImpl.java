package com.bkanent.contract.service;

import com.bkanent.contract.entity.ContractEntity;
import com.bkanent.common.rpc.ContractRpcService;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class ContractRpcServiceImpl implements ContractRpcService {

    private final ContractDomainService contractDomainService;

    public ContractRpcServiceImpl(ContractDomainService contractDomainService) {
        this.contractDomainService = contractDomainService;
    }

    @Override
    public String checkClauseRisk(Long contractId) {
        ContractEntity entity = contractDomainService.getById(contractId);
        if (entity == null) {
            return "contract not found";
        }
        if ("DISPUTE".equalsIgnoreCase(entity.getStatus())) {
            return "high risk: contract is already in dispute status";
        }
        return "medium risk: verify signatures, owner identity, and expiry date";
    }
}
