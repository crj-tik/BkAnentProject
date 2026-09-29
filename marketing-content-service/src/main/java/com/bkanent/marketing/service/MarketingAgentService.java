package com.bkanent.marketing.service;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;

/**
 * MarketingAgentService 营销 Agent 适配服务。
 */
public interface MarketingAgentService {

    AgentCard getAgentCard();

    AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request);

    /**
     * LLM 营销文案生成：依据房源摘要与目标平台生成文案正文。
     * LLM 调用失败时抛出异常，由调用方决定回退策略。
     */
    String generateCopy(String listingSummary, String platform);
}
