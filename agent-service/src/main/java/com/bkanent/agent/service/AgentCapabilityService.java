package com.bkanent.agent.service;

import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.model.MarketingContentDTO;

import java.util.List;

/**
 * AgentCapabilityService 服务类。
 */
public interface AgentCapabilityService {

    List<MarketingContentDTO> publishListingByPrompt(String rawInput);

    String generateKpiNarrative(List<KpiSummaryDTO> kpis);

}
