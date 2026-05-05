package com.bkanent.settlement.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.SettlementSummaryDTO;
import com.bkanent.common.rpc.SettlementRpcService;
import com.bkanent.settlement.entity.SettlementRecordEntity;
import com.bkanent.settlement.service.SettlementDomainService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/settlements")
public class SettlementController {

    private final SettlementRpcService settlementRpcService;
    private final SettlementDomainService settlementDomainService;

    public SettlementController(SettlementRpcService settlementRpcService, SettlementDomainService settlementDomainService) {
        this.settlementRpcService = settlementRpcService;
        this.settlementDomainService = settlementDomainService;
    }

    @GetMapping("/commission")
    public ApiResponse<SettlementSummaryDTO> commission(@RequestParam Long employeeId, @RequestParam String month) {
        SettlementRecordEntity entity = settlementDomainService.findByEmployeeAndMonth(employeeId, month);
        String payoutStatus = entity == null ? "NOT_FOUND" : entity.getPayoutStatus();
        return ApiResponse.ok(new SettlementSummaryDTO(
                employeeId,
                month,
                settlementRpcService.queryMonthlyCommission(employeeId, month),
                payoutStatus
        ));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("settlement-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
