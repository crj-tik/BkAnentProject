package com.bkanent.common.rpc;

import com.bkanent.common.model.PublishRequest;
import com.bkanent.common.marketing.campaign.MarketingContentDraftResponse;

import java.util.List;

/**
 * Agent RPC 接口定义。
 */
public interface AgentRpcService {

    /** Creates DRAFT marketing content; publication must use the campaign plan and Supervisor approval flow. */
    List<MarketingContentDraftResponse> createMarketingDraft(PublishRequest request);

}
