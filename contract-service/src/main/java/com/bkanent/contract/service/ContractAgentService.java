package com.bkanent.contract.service;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.contract.model.ContractDetailResponse;
import com.bkanent.contract.model.ContractRiskAssessment;

public interface ContractAgentService {

    AgentCard getAgentCard();

    AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request);

    /**
     * LLM 合同风险评审：基于合同详情生成结构化风险结论。
     * LLM 调用失败或解析失败时抛出异常，由调用方决定回退策略。
     */
    ContractRiskAssessment reviewRisks(ContractDetailResponse detail);
}
