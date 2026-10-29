package com.bkanent.business.service;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;

public interface TradeAgentService {

    AgentCard getAgentCard();

    AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request);
}
