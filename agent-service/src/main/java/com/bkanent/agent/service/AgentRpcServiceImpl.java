package com.bkanent.agent.service;

import com.bkanent.agent.mcp.MilvusMcpTool;
import com.bkanent.agent.mcp.MilvusSearchResult;
import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.AgentRpcService;
import com.bkanent.common.rpc.BusinessRpcService;
import com.bkanent.common.rpc.CompareEngineRpcService;
import com.bkanent.common.rpc.MarketingContentRpcService;
import com.bkanent.common.rpc.MediaWorkerRpcService;
import com.bkanent.common.rpc.PromotionRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@DubboService
public class AgentRpcServiceImpl implements AgentRpcService {

    private static final String MARKETING_COPY_SYSTEM_PROMPT = """
            You are a real-estate marketing copy assistant.
            Generate concise and publishable Chinese property copywriting.
            Keep the tone factual, attractive, and avoid exaggerated claims.
            """;

    private static final String KPI_NARRATIVE_SYSTEM_PROMPT = """
            You are a real-estate business analysis assistant.
            Produce a short monthly KPI summary for management.
            Include current status, risks, and recommended actions.
            """;

    @DubboReference(check = false)
    private MediaWorkerRpcService mediaWorkerRpcService;

    @DubboReference(check = false)
    private MarketingContentRpcService marketingContentRpcService;

    @DubboReference(check = false)
    private PromotionRpcService promotionRpcService;

    @DubboReference(check = false)
    private CompareEngineRpcService compareEngineRpcService;

    @DubboReference(check = false)
    private BusinessRpcService businessRpcService;

    private final MilvusMcpTool milvusMcpTool;
    private final QwenChatService qwenChatService;

    public AgentRpcServiceImpl(MilvusMcpTool milvusMcpTool, QwenChatService qwenChatService) {
        this.milvusMcpTool = milvusMcpTool;
        this.qwenChatService = qwenChatService;
    }

    @Override
    public List<MarketingContentDTO> publishListingByPrompt(String rawInput) {
        List<String> assets = mediaWorkerRpcService == null ? List.of() : mediaWorkerRpcService.generateAssets(1L, rawInput);
        String copywriting = qwenChatService.complete(MARKETING_COPY_SYSTEM_PROMPT,
                "Generate one Chinese property marketing copy for the following request:\n" + rawInput);
        MarketingContentDTO content = new MarketingContentDTO(1L, "douyin", copywriting, assets, "DRAFT");
        if (marketingContentRpcService != null) {
            marketingContentRpcService.saveGeneratedContents(List.of(content));
        }
        if (promotionRpcService != null) {
            promotionRpcService.publish(content);
        }
        return List.of(content);
    }

    @Override
    public String generateKpiNarrative(List<KpiSummaryDTO> kpis) {
        List<KpiSummaryDTO> source = kpis == null || kpis.isEmpty()
                ? businessRpcService == null ? List.of() : businessRpcService.getMonthlyKpis("2026-04")
                : kpis;
        List<MilvusSearchResult> knowledgeResults = milvusMcpTool.search("monthly kpi benchmark", 2);
        String knowledge = knowledgeResults.stream()
                .map(MilvusSearchResult::content)
                .collect(Collectors.joining("\n"));
        String kpiSummary = source.stream()
                .map(kpi -> "employeeId=" + kpi.employeeId()
                        + ", employeeName=" + kpi.employeeName()
                        + ", closedDeals=" + kpi.closedDeals()
                        + ", newListings=" + kpi.newListings()
                        + ", newCustomers=" + kpi.newCustomers()
                        + ", completionRate=" + kpi.completionRate())
                .collect(Collectors.joining("\n"));
        return qwenChatService.complete(KPI_NARRATIVE_SYSTEM_PROMPT,
                "KPI data:\n" + kpiSummary + "\n\nReference knowledge:\n" + knowledge);
    }

    @Override
    public CompareReportDTO analyzeListings(List<Long> listingIds) {
        if (compareEngineRpcService != null) {
            return compareEngineRpcService.compareListings(listingIds);
        }
        return new CompareReportDTO(new ArrayList<>(), "| listing | conclusion |\n| --- | --- |", "compare engine not connected yet");
    }
}
