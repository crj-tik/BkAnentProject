package com.bkanent.settlement.service;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;

public interface SettlementAgentService {

    AgentCard getAgentCard();

    AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request);
}
