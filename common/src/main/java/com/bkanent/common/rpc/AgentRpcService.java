package com.bkanent.common.rpc;

import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.model.MarketingContentDTO;

import java.util.List;

/**
 * Agent RPC 接口定义。
 */
public interface AgentRpcService {

    /**
     * 业务方法：publishListingByPrompt。
     */
    List<MarketingContentDTO> publishListingByPrompt(String rawInput);

    /**
     * 业务方法：generateKpiNarrative。
     */
    String generateKpiNarrative(List<KpiSummaryDTO> kpis);

    /**
     * 业务方法：analyzeListings。
     */
    CompareReportDTO analyzeListings(List<Long> listingIds);

    /**
     * 业务方法：generateCompareConclusion。
     */
    String generateCompareConclusion(String comparePrompt);
}
