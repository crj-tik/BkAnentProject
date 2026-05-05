package com.bkanent.common.rpc;

import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.model.MarketingContentDTO;

import java.util.List;

public interface AgentRpcService {

    List<MarketingContentDTO> publishListingByPrompt(String rawInput);

    String generateKpiNarrative(List<KpiSummaryDTO> kpis);

    CompareReportDTO analyzeListings(List<Long> listingIds);
}
