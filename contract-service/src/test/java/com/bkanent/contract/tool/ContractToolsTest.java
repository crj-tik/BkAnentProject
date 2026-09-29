package com.bkanent.contract.tool;

import com.bkanent.contract.config.ContractAgentProperties;
import com.bkanent.contract.model.ContractAttachmentResponse;
import com.bkanent.contract.model.ContractDetailResponse;
import com.bkanent.contract.model.ContractRiskAssessment;
import com.bkanent.contract.service.ContractAgentService;
import com.bkanent.contract.service.ContractManagementService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ContractToolsTest {

    private final ContractManagementService managementService = Mockito.mock(ContractManagementService.class);
    private final ContractAgentService agentService = Mockito.mock(ContractAgentService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ContractAgentService> agentServiceProvider = Mockito.mock(ObjectProvider.class);
    private final ContractAgentProperties properties = new ContractAgentProperties();

    private final ContractTools tools = new ContractTools(managementService, properties, agentServiceProvider);

    @Test
    void llmAssessmentSucceedsWithSourceLlmAndLegacyFields() {
        when(managementService.getContractDetail(101L)).thenReturn(pendingDetail());
        when(agentServiceProvider.getObject()).thenReturn(agentService);
        when(agentService.reviewRisks(any())).thenReturn(new ContractRiskAssessment(
                "high", List.of("OCR 摘要缺失，无法核对交付条款"), List.of("补充 OCR 后复审"), "条款级评审结论"));

        Map<String, Object> output = tools.reviewContractRisks(101L);

        assertThat(output.get("source")).isEqualTo("llm");
        assertThat(output.get("riskLevel")).isEqualTo("high");
        assertThat(output.get("summary")).isEqualTo("条款级评审结论");
        assertThat(output.get("contractNo")).isEqualTo("HT-101");
        assertThat(output.get("sealStatus")).isEqualTo("PENDING");
        assertThat(output.get("archiveStatus")).isEqualTo("ARCHIVED");
        assertThat(output.get("attachmentCount")).isEqualTo(1);
    }

    @Test
    void llmFailureFallsBackToRuleWithSourceRule() {
        when(managementService.getContractDetail(101L)).thenReturn(pendingDetail());
        when(agentServiceProvider.getObject()).thenReturn(agentService);
        when(agentService.reviewRisks(any())).thenThrow(new IllegalStateException("LLM timeout"));

        Map<String, Object> output = tools.reviewContractRisks(101L);

        assertThat(output.get("source")).isEqualTo("rule");
        assertThat(output.get("riskLevel")).isEqualTo("high");
        assertThat(output.get("riskFactors")).isEqualTo(List.of("seal_pending", "ocr_summary_missing"));
        assertThat((List<?>) output.get("recommendedActions")).isNotEmpty();
    }

    @Test
    void switchOffGoesStraightToRule() {
        properties.setLlmRiskReviewEnabled(false);
        when(managementService.getContractDetail(101L)).thenReturn(pendingDetail());

        Map<String, Object> output = tools.reviewContractRisks(101L);

        assertThat(output.get("source")).isEqualTo("rule");
        Mockito.verifyNoInteractions(agentServiceProvider);
    }

    @Test
    void cleanContractUnderRuleReviewIsLowRisk() {
        properties.setLlmRiskReviewEnabled(false);
        when(managementService.getContractDetail(202L)).thenReturn(cleanDetail());

        Map<String, Object> output = tools.reviewContractRisks(202L);

        assertThat(output.get("source")).isEqualTo("rule");
        assertThat(output.get("riskLevel")).isEqualTo("low");
        assertThat(output.get("riskFactors")).isEqualTo(List.of("no_major_risk_detected"));
    }

    private ContractDetailResponse pendingDetail() {
        return new ContractDetailResponse(
                101L, null, "HT-101", "买卖合同", "SECOND_HAND", "DRAFT", "2026-12-31",
                9L, 5L, "张三", "甲方", "乙方", new BigDecimal("1500000"),
                null, null, null, null, null, null,
                "ARCHIVED", "PENDING", null, null, null, null,
                List.of(new ContractAttachmentResponse(
                        1L, 101L, "SIGN", "合同扫描件.pdf", "oss://contract/101.pdf",
                        "DONE", "合同正文", "{}", "mock-ocr", "2026-09-01 10:00:00", null)));
    }

    private ContractDetailResponse cleanDetail() {
        return new ContractDetailResponse(
                202L, null, "HT-202", "租赁合同", "RENT", "SEALED", "2026-12-31",
                9L, 5L, "李四", "甲方", "乙方", new BigDecimal("80000"),
                null, null, null, null, null, null,
                "ARCHIVED", "SEALED", null, null, "OCR 摘要完整", null,
                List.of());
    }
}
