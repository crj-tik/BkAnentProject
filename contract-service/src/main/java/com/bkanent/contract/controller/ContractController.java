package com.bkanent.contract.controller;

import com.bkanent.contract.entity.ContractEntity;
import com.bkanent.contract.service.ContractDomainService;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.ContractSummaryDTO;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.rpc.ContractRpcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/contracts")
public class ContractController {

    private final ContractRpcService contractRpcService;
    private final ContractDomainService contractDomainService;

    public ContractController(ContractRpcService contractRpcService, ContractDomainService contractDomainService) {
        this.contractRpcService = contractRpcService;
        this.contractDomainService = contractDomainService;
    }

    @GetMapping("/{id}")
    public ApiResponse<ContractSummaryDTO> detail(@PathVariable Long id) {
        ContractEntity entity = contractDomainService.getById(id);
        if (entity == null) {
            return ApiResponse.fail("CONTRACT_404", "contract not found");
        }
        return ApiResponse.ok(new ContractSummaryDTO(entity.getId(), entity.getContractType(), entity.getStatus(), entity.getExpiryDate()));
    }

    @GetMapping("/{id}/risk-check")
    public ApiResponse<String> riskCheck(@PathVariable Long id) {
        return ApiResponse.ok(contractRpcService.checkClauseRisk(id));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("contract-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
