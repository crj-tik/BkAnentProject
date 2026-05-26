package com.bkanent.business.tool;

import com.bkanent.business.model.EmployeeDailyWorkloadResponse;
import com.bkanent.business.model.KpiAssessmentResponse;
import com.bkanent.business.model.ListingTurnoverReportResponse;
import com.bkanent.business.model.RankingItemResponse;
import com.bkanent.business.model.StoreDashboardResponse;
import com.bkanent.business.service.BusinessAnalyticsService;
import com.bkanent.common.model.KpiSummaryDTO;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TradeTools {

    private final BusinessAnalyticsService businessAnalyticsService;

    public TradeTools(BusinessAnalyticsService businessAnalyticsService) {
        this.businessAnalyticsService = businessAnalyticsService;
    }

    @Tool(description = "Query monthly KPI summaries for all agents. Returns deal counts, new listings, new customers, and completion rates per agent.")
    public List<KpiSummaryDTO> queryMonthlyKpis(
            @ToolParam(description = "Month in yyyy-MM format, e.g. 2026-01") String month) {
        return businessAnalyticsService.listMonthlyKpis(month);
    }

    @Tool(description = "Query listing turnover reports for a given month. Returns listing counts, turnover days, and region distributions.")
    public List<ListingTurnoverReportResponse> queryListingTurnover(
            @ToolParam(description = "Month in yyyy-MM format") String month) {
        return businessAnalyticsService.listTurnoverReports(month);
    }

    @Tool(description = "Get the dashboard for a specific store, including active listings, viewing counts, deal counts, and satisfaction scores.")
    public StoreDashboardResponse getStoreDashboard(
            @ToolParam(description = "Store name") String storeName) {
        return businessAnalyticsService.getStoreDashboard(storeName);
    }

    @Tool(description = "Calculate KPI assessments (completion %, conversion rate, satisfaction) for all agents in a month.")
    public List<KpiAssessmentResponse> calculateKpiAssessments(
            @ToolParam(description = "Month in yyyy-MM format") String month) {
        return businessAnalyticsService.calculateKpiAssessments(month);
    }

    @Tool(description = "Get agent rankings by performance amount for a given month, scope (PERSONAL/STORE/REGION), and top-N.")
    public List<RankingItemResponse> queryRankings(
            @ToolParam(description = "Month in yyyy-MM format") String month,
            @ToolParam(description = "Scope: PERSONAL, STORE, or REGION") String scope,
            @ToolParam(description = "Number of top entries to return, default 10") int topN) {
        return businessAnalyticsService.listRankings(month, scope, topN);
    }

    @Tool(description = "Get daily workload statistics for a specific date. Shows agent activity levels including viewings, new listings, new customers, and follow-ups.")
    public List<EmployeeDailyWorkloadResponse> queryDailyWorkloads(
            @ToolParam(description = "Date in yyyy-MM-dd format") String statDate) {
        return businessAnalyticsService.listDailyWorkloads(statDate);
    }
}