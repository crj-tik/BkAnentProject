package com.bkanent.agent.rpc;

import com.bkanent.agent.milvus.listing.ListingMilvusService;
import com.bkanent.agent.service.AgentCapabilityService;
import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.AgentRpcService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * AgentRpcServiceImpl RPC 服务实现。
 */
@DubboService
public class AgentRpcServiceImpl implements AgentRpcService {

    private final AgentCapabilityService agentCapabilityService;
    private final ListingMilvusService listingMilvusService;

    public AgentRpcServiceImpl(AgentCapabilityService agentCapabilityService,
                               ListingMilvusService listingMilvusService) {
        this.agentCapabilityService = agentCapabilityService;
        this.listingMilvusService = listingMilvusService;
    }

    @Override
    public List<MarketingContentDTO> publishListingByPrompt(String rawInput) {
        return agentCapabilityService.publishListingByPrompt(rawInput);
    }

    @Override
    public String generateKpiNarrative(List<KpiSummaryDTO> kpis) {
        return agentCapabilityService.generateKpiNarrative(kpis);
    }

    @Override
    public CompareReportDTO analyzeListings(List<Long> listingIds) {
        return agentCapabilityService.analyzeListings(listingIds);
    }

    @Override
    public String generateCompareConclusion(String comparePrompt) {
        return agentCapabilityService.generateCompareConclusion(comparePrompt);
    }

    @Override
    public void syncListingKnowledge(Long listingId) {
        listingMilvusService.indexListing(listingId, null);
    }

    @Override
    public void deleteListingKnowledge(Long listingId) {
        listingMilvusService.deleteListing(listingId, null);
    }
}
