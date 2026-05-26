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
}
