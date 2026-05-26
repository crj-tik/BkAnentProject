package com.bkanent.contract.service;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;

public interface ContractAgentService {

    AgentCard getAgentCard();

    AgentTaskInvokeResponse invoke(AgentTaskInvokeRequest request);
}
