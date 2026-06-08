package com.bkanent.contract.mcp;

import com.bkanent.common.tool.McpTool;
import com.bkanent.contract.model.ContractDetailResponse;
import com.bkanent.contract.service.ContractManagementService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ContractMcpTools implements McpTool {

    private final ContractManagementService contractManagementService;

    public ContractMcpTools(ContractManagementService contractManagementService) {
        this.contractManagementService = contractManagementService;
    }

    @Tool(description = "Get the full detail of a contract by its ID, including status, seal status, attachments, OCR summary, and archive status.")
    public ContractDetailResponse getContractDetail(
            @ToolParam(description = "Contract ID") Long contractId) {
        return contractManagementService.getContractDetail(contractId);
    }

    @Tool(description = "List contracts filtered by contract type and/or status. Returns basic info for each matching contract.")
    public List<ContractDetailResponse> listContracts(
            @ToolParam(description = "Contract type filter, e.g. SECOND_HAND, RENT. Pass empty string to skip filter.") String contractType,
            @ToolParam(description = "Status filter, e.g. DRAFT, SEALED, ARCHIVED. Pass empty string to skip filter.") String status) {
        return contractManagementService.listContracts(
                contractType == null || contractType.isBlank() ? null : contractType,
                status == null || status.isBlank() ? null : status);
    }

    @Tool(description = "Review contract risks based on seal status, archive status, OCR completeness, and attachment count.")
    public Map<String, Object> reviewContractRisks(
            @ToolParam(description = "Contract ID to review") Long contractId) {
        ContractDetailResponse detail = contractManagementService.getContractDetail(contractId);
        List<String> risks = new java.util.ArrayList<>();
        if (!"SEALED".equalsIgnoreCase(detail.sealStatus())) {
            risks.add("seal_pending");
        }
        if (!"ARCHIVED".equalsIgnoreCase(detail.archiveStatus())) {
            risks.add("archive_pending");
        }
        if (detail.ocrSummary() == null || detail.ocrSummary().isBlank()) {
            risks.add("ocr_summary_missing");
        }
        if (risks.isEmpty()) {
            risks.add("no_major_risk_detected");
        }
        return Map.of(
                "contractId", contractId,
                "contractNo", detail.contractNo(),
                "status", detail.status(),
                "sealStatus", detail.sealStatus(),
                "archiveStatus", detail.archiveStatus(),
                "risks", List.copyOf(risks),
                "attachmentCount", detail.attachments() == null ? 0 : detail.attachments().size()
        );
    }
}
