package com.bkanent.contract.model;

import java.util.List;

/**
 * LLM 合同风险评审结论。
 *
 * @param riskLevel          风险等级：low / medium / high
 * @param riskFactors        风险因素（条款级依据）
 * @param recommendedActions 处置建议
 * @param summary            理由摘要
 */
public record ContractRiskAssessment(
        String riskLevel,
        List<String> riskFactors,
        List<String> recommendedActions,
        String summary
) {
}
