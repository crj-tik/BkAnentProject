package com.bkanent.contract.tool;

import com.bkanent.contract.config.ContractAgentProperties;
import com.bkanent.contract.model.ContractDetailResponse;
import com.bkanent.contract.model.ContractRiskAssessment;
import com.bkanent.contract.service.ContractAgentService;
import com.bkanent.contract.service.ContractManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ContractTools {

    private static final Logger log = LoggerFactory.getLogger(ContractTools.class);

    private final ContractManagementService contractManagementService;
    private final ContractAgentProperties properties;
    private final ObjectProvider<ContractAgentService> contractAgentServiceProvider;

    public ContractTools(ContractManagementService contractManagementService,
                         ContractAgentProperties properties,
                         ObjectProvider<ContractAgentService> contractAgentServiceProvider) {
        this.contractManagementService = contractManagementService;
        this.properties = properties;
        this.contractAgentServiceProvider = contractAgentServiceProvider;
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

    @Tool(description = "Review contract risks. Combines an LLM-driven clause-level assessment (when enabled and available) "
            + "with deterministic status checks on seal, archive, and OCR completeness. Returns risk level, risk factors, "
            + "recommended actions, and the assessment source (llm or rule).")
    public Map<String, Object> reviewContractRisks(
            @ToolParam(description = "Contract ID to review") Long contractId) {
        ContractDetailResponse detail = contractManagementService.getContractDetail(contractId);

        if (properties.isLlmRiskReviewEnabled()) {
            try {
                ContractRiskAssessment assessment = contractAgentServiceProvider.getObject().reviewRisks(detail);
                return buildOutput(detail, assessment.riskLevel(), assessment.riskFactors(),
                        assessment.recommendedActions(), assessment.summary(), "llm");
            } catch (Exception e) {
                log.warn("LLM risk review failed for contract {}, falling back to rule-based: {}",
                        contractId, e.getMessage());
            }
        }
        return ruleBasedReview(detail);
    }

    private Map<String, Object> ruleBasedReview(ContractDetailResponse detail) {
        List<String> risks = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        if (!"SEALED".equalsIgnoreCase(detail.sealStatus())) {
            risks.add("seal_pending");
            actions.add("完成用印后再推进签署流程");
        }
        if (!"ARCHIVED".equalsIgnoreCase(detail.archiveStatus())) {
            risks.add("archive_pending");
            actions.add("完成合同归档");
        }
        if (detail.ocrSummary() == null || detail.ocrSummary().isBlank()) {
            risks.add("ocr_summary_missing");
            actions.add("补充 OCR 摘要以便条款级复核");
        }
        String riskLevel;
        String summary;
        if (risks.isEmpty()) {
            risks.add("no_major_risk_detected");
            riskLevel = "low";
            actions.add("可正常推进签署");
            summary = "规则评审：用印、归档与 OCR 完整性均无异常。";
        } else {
            riskLevel = risks.size() >= 2 ? "high" : "medium";
            summary = "规则评审：发现 " + risks.size() + " 项待办风险（LLM 评审不可用或未开启）。";
        }
        return buildOutput(detail, riskLevel, risks, actions, summary, "rule");
    }

    private Map<String, Object> buildOutput(ContractDetailResponse detail,
                                            String riskLevel,
                                            List<String> riskFactors,
                                            List<String> recommendedActions,
                                            String summary,
                                            String source) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("contractId", detail.id());
        output.put("contractNo", detail.contractNo());
        output.put("status", detail.status());
        output.put("sealStatus", detail.sealStatus());
        output.put("archiveStatus", detail.archiveStatus());
        output.put("attachmentCount", detail.attachments() == null ? 0 : detail.attachments().size());
        output.put("riskLevel", riskLevel);
        output.put("riskFactors", List.copyOf(riskFactors));
        output.put("recommendedActions", List.copyOf(recommendedActions));
        output.put("summary", summary);
        output.put("source", source);
        return output;
    }
}
