package com.bkanent.agent.rpc;

import com.bkanent.agent.service.AgentCapabilityService;
import com.bkanent.common.model.PublishRequest;
import com.bkanent.common.marketing.campaign.MarketingContentDraftResponse;
import com.bkanent.common.rpc.AgentRpcService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * AgentRpcServiceImpl RPC 服务实现。
 */
@DubboService
public class AgentRpcServiceImpl implements AgentRpcService {

    private final AgentCapabilityService agentCapabilityService;

    public AgentRpcServiceImpl(AgentCapabilityService agentCapabilityService) {
        this.agentCapabilityService = agentCapabilityService;
    }

    @Override
    public List<MarketingContentDraftResponse> createMarketingDraft(PublishRequest request) {
        return agentCapabilityService.createMarketingDraft(request);
    }

}
