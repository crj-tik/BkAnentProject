package com.bkanent.agent.service;

import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.model.PublishRequest;
import com.bkanent.common.marketing.campaign.MarketingContentDraftResponse;

import java.util.List;

/**
 * AgentCapabilityService 服务类。
 */
public interface AgentCapabilityService {

    List<MarketingContentDraftResponse> createMarketingDraft(PublishRequest request);

    String generateKpiNarrative(List<KpiSummaryDTO> kpis);

}
