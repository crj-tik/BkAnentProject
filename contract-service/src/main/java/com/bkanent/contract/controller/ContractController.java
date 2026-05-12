package com.bkanent.contract.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.rpc.ContractRpcService;
import com.bkanent.contract.model.ContractArchiveRequest;
import com.bkanent.contract.model.ContractAttachmentOcrRequest;
import com.bkanent.contract.model.ContractAttachmentResponse;
import com.bkanent.contract.model.ContractDetailResponse;
import com.bkanent.contract.model.ContractSealRequest;
import com.bkanent.contract.model.ContractStatusUpdateRequest;
import com.bkanent.contract.model.ContractTemplateResponse;
import com.bkanent.contract.model.ContractTemplateUpsertRequest;
import com.bkanent.contract.model.ContractUpsertRequest;
import com.bkanent.contract.service.ContractManagementService;
import com.bkanent.contract.service.ContractReminderService;
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
 * Contract workflow controller.
 */
@RestController
@RequestMapping("/contracts")
public class ContractController {

    private final ContractRpcService contractRpcService;
    private final ContractManagementService contractManagementService;
    private final ContractReminderService contractReminderService;

    public ContractController(ContractRpcService contractRpcService,
                              ContractManagementService contractManagementService,
                              ContractReminderService contractReminderService) {
        this.contractRpcService = contractRpcService;
        this.contractManagementService = contractManagementService;
        this.contractReminderService = contractReminderService;
    }

    @PostMapping("/templates")
    public ApiResponse<ContractTemplateResponse> saveTemplate(@RequestBody ContractTemplateUpsertRequest request) {
        return ApiResponse.ok(contractManagementService.saveTemplate(request));
    }

    @GetMapping("/templates")
    public ApiResponse<List<ContractTemplateResponse>> listTemplates(@RequestParam(required = false) String contractType) {
        return ApiResponse.ok(contractManagementService.listTemplates(contractType));
    }

    @PostMapping
    public ApiResponse<ContractDetailResponse> saveContract(@RequestBody ContractUpsertRequest request) {
        return ApiResponse.ok(contractManagementService.saveContract(request));
    }

    @GetMapping
    public ApiResponse<List<ContractDetailResponse>> listContracts(@RequestParam(required = false) String contractType,
                                                                   @RequestParam(required = false) String status) {
        return ApiResponse.ok(contractManagementService.listContracts(contractType, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<ContractDetailResponse> detail(@PathVariable Long id) {
        ContractDetailResponse response = contractManagementService.getContractDetail(id);
        if (response == null) {
            return ApiResponse.fail("CONTRACT_404", "Contract not found");
        }
        return ApiResponse.ok(response);
    }

    @PutMapping("/{id}/status")
    public ApiResponse<ContractDetailResponse> updateStatus(@PathVariable Long id,
                                                            @RequestBody ContractStatusUpdateRequest request) {
        return ApiResponse.ok(contractManagementService.updateStatus(id, request));
    }

    @PostMapping("/{id}/attachments/ocr")
    public ApiResponse<ContractAttachmentResponse> recognizeAttachment(@PathVariable Long id,
                                                                       @RequestBody ContractAttachmentOcrRequest request) {
        return ApiResponse.ok(contractManagementService.recognizeAttachment(id, request));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<ContractDetailResponse> archive(@PathVariable Long id,
                                                       @RequestBody ContractArchiveRequest request) {
        return ApiResponse.ok(contractManagementService.archiveContract(id, request));
    }

    @PostMapping("/{id}/seal")
    public ApiResponse<ContractDetailResponse> seal(@PathVariable Long id,
                                                    @RequestBody ContractSealRequest request) {
        return ApiResponse.ok(contractManagementService.sealContract(id, request));
    }

    @GetMapping("/{id}/risk-check")
    public ApiResponse<String> riskCheck(@PathVariable Long id) {
        return ApiResponse.ok(contractRpcService.checkClauseRisk(id));
    }

    @GetMapping("/pending-sign/remind")
    public ApiResponse<Integer> remindPendingSign(@RequestParam(defaultValue = "3") int days) {
        return ApiResponse.ok(contractReminderService.sendPendingSignReminders(days));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("contract-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
