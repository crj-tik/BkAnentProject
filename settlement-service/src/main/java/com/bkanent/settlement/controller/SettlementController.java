package com.bkanent.settlement.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.SettlementSummaryDTO;
import com.bkanent.common.rpc.SettlementRpcService;
import com.bkanent.settlement.entity.SettlementRecordEntity;
import com.bkanent.settlement.model.SettlementAutoGenerateResponse;
import com.bkanent.settlement.model.SettlementBankCallbackRequest;
import com.bkanent.settlement.model.SettlementCalculateRequest;
import com.bkanent.settlement.model.SettlementDetailResponse;
import com.bkanent.settlement.model.SettlementMonthlySummaryResponse;
import com.bkanent.settlement.model.SettlementPayoutBatchResponse;
import com.bkanent.settlement.model.SettlementPayoutUpdateRequest;
import com.bkanent.settlement.model.SettlementRuleResponse;
import com.bkanent.settlement.model.SettlementRuleUpsertRequest;
import com.bkanent.settlement.service.SettlementDomainService;
import com.bkanent.settlement.service.SettlementManagementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Settlement management controller.
 */
@RestController
@RequestMapping("/settlements")
public class SettlementController {

    private final SettlementRpcService settlementRpcService;
    private final SettlementDomainService settlementDomainService;
    private final SettlementManagementService settlementManagementService;

    public SettlementController(SettlementRpcService settlementRpcService,
                                SettlementDomainService settlementDomainService,
                                SettlementManagementService settlementManagementService) {
        this.settlementRpcService = settlementRpcService;
        this.settlementDomainService = settlementDomainService;
        this.settlementManagementService = settlementManagementService;
    }

    @PostMapping("/rules")
    public ApiResponse<SettlementRuleResponse> saveRule(@RequestBody SettlementRuleUpsertRequest request) {
        return ApiResponse.ok(settlementManagementService.saveRule(request));
    }

    @GetMapping("/rules")
    public ApiResponse<List<SettlementRuleResponse>> listRules(@RequestParam(required = false) String contractType) {
        return ApiResponse.ok(settlementManagementService.listRules(contractType));
    }

    @PostMapping("/calculate")
    public ApiResponse<SettlementDetailResponse> calculate(@RequestBody SettlementCalculateRequest request) {
        return ApiResponse.ok(settlementManagementService.calculateSettlement(request));
    }

    @PostMapping("/auto-generate")
    public ApiResponse<SettlementAutoGenerateResponse> autoGenerate(@RequestParam String month) {
        return ApiResponse.ok(settlementManagementService.autoGenerateByContracts(month));
    }

    @GetMapping("/{id}")
    public ApiResponse<SettlementDetailResponse> detail(@PathVariable Long id) {
        SettlementDetailResponse response = settlementManagementService.getSettlementDetail(id);
        if (response == null) {
            return ApiResponse.fail("SETTLEMENT_404", "结算记录不存在");
        }
        return ApiResponse.ok(response);
    }

    @GetMapping
    public ApiResponse<List<SettlementDetailResponse>> listByMonth(@RequestParam String month) {
        return ApiResponse.ok(settlementManagementService.listSettlementByMonth(month));
    }

    @PutMapping("/{id}/payout-status")
    public ApiResponse<SettlementDetailResponse> updatePayoutStatus(@PathVariable Long id,
                                                                    @RequestBody SettlementPayoutUpdateRequest request) {
        return ApiResponse.ok(settlementManagementService.updatePayoutStatus(id, request));
    }

    @GetMapping("/monthly-summary")
    public ApiResponse<List<SettlementMonthlySummaryResponse>> monthlySummary(@RequestParam String month,
                                                                              @RequestParam(required = false) String summaryScope) {
        return ApiResponse.ok(settlementManagementService.summarizeMonthlyCommission(month, summaryScope));
    }

    @PostMapping("/payout-batches")
    public ApiResponse<SettlementPayoutBatchResponse> createPayoutBatch(@RequestParam String month) {
        SettlementPayoutBatchResponse response = settlementManagementService.createPayoutBatch(month);
        if (response == null) {
            return ApiResponse.fail("SETTLEMENT_BATCH_EMPTY", "当前月份没有待发放的结算记录");
        }
        return ApiResponse.ok(response);
    }

    @PutMapping("/payout-batches/{id}/paid")
    public ApiResponse<SettlementPayoutBatchResponse> markBatchPaid(@PathVariable Long id) {
        return ApiResponse.ok(settlementManagementService.markBatchPaid(id));
    }

    @PostMapping("/payout-batches/bank-callback")
    public ApiResponse<SettlementPayoutBatchResponse> bankCallback(@RequestBody SettlementBankCallbackRequest request) {
        return ApiResponse.ok(settlementManagementService.handleBankCallback(request));
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
