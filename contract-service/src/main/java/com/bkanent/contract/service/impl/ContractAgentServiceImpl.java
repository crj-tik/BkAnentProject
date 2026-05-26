package com.bkanent.contract.service.impl;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.contract.model.ContractAttachmentResponse;
import com.bkanent.contract.model.ContractDetailResponse;
import com.bkanent.contract.service.ContractAgentService;
import com.bkanent.contract.service.ContractManagementService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContractAgentServiceImpl implements ContractAgentService {

    private final ContractManagementService contractManagementService;

    public ContractAgentServiceImpl(ContractManagementService contractManagementService) {
        this.contractManagementService = contractManagementService;
    }

    @Override
    public AgentCard getAgentCard() {
        return new AgentCard(
                "contract-agent",
                "Contract Agent",
                "Responsible for contract parsing and risk review",
                "1.0.0",
                List.of("contract-parse", "contract-risk-review"),
                List.of("contract"),
                true,
                true,
                "/a2a",
                List.of("text", "json"),
                List.of("text", "json")
        );
    }

    @Override
    public AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request) {
        Map<String, Object> context = request.structuredContext() == null ? Map.of() : request.structuredContext();
        Long contractId = asLong(context.get("contractId"));
        ContractDetailResponse detail = contractId == null ? null : contractManagementService.getContractDetail(contractId);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resultType", "contract_review");
        output.put("contentType", "contract_summary");
        output.put("artifactTypeHint", "contract_review_detail");
        output.put("summary", detail == null ? "No contract found for review" : buildSummary(detail));
        output.put("risks", detail == null ? List.of("contract_not_found") : buildRisks(detail));
        output.put("clauses", detail == null ? List.of() : buildClauses(detail));
        output.put("contractStatus", detail == null ? "UNKNOWN" : detail.status());
        output.put("attachmentCount", detail == null || detail.attachments() == null ? 0 : detail.attachments().size());

        return new AgentTaskInvokeResponse(
                request.sessionId(),
                request.taskId(),
                "contract-agent",
                "COMPLETED",
                output,
                List.of(),
                List.of("trade.feasibility_analysis", "settlement.prepare"),
                String.valueOf(output.get("summary")),
                request.traceId()
        );
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String buildSummary(ContractDetailResponse detail) {
        return "Contract " + detail.contractNo() + " status " + detail.status() + ", seal status " + detail.sealStatus();
    }

    private List<String> buildRisks(ContractDetailResponse detail) {
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
        return List.copyOf(risks);
    }

    private List<Map<String, Object>> buildClauses(ContractDetailResponse detail) {
        return List.of(
                Map.of("clause", "contractType", "value", detail.contractType()),
                Map.of("clause", "status", "value", detail.status()),
                Map.of("clause", "archiveStatus", "value", detail.archiveStatus()),
                Map.of("clause", "sealStatus", "value", detail.sealStatus())
        );
    }
}
