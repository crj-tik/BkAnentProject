package com.bkanent.business.controller;

import com.bkanent.business.model.BigDataDashboardResponse;
import com.bkanent.business.model.EmployeeDailyWorkloadResponse;
import com.bkanent.business.model.KpiAssessmentResponse;
import com.bkanent.business.model.ListingTurnoverReportResponse;
import com.bkanent.business.model.RankingItemResponse;
import com.bkanent.business.model.StoreDashboardResponse;
import com.bkanent.business.service.BusinessAnalyticsService;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.KpiSummaryDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Business analytics controller.
 */
@RestController
@RequestMapping("/business")
public class BusinessController {

    private final BusinessAnalyticsService businessAnalyticsService;

    public BusinessController(BusinessAnalyticsService businessAnalyticsService) {
        this.businessAnalyticsService = businessAnalyticsService;
    }

    @GetMapping("/kpis/monthly")
    public ApiResponse<List<KpiSummaryDTO>> monthlyKpis(@RequestParam String month) {
        return ApiResponse.ok(businessAnalyticsService.listMonthlyKpis(month));
    }

    @GetMapping("/workloads/daily")
    public ApiResponse<List<EmployeeDailyWorkloadResponse>> dailyWorkloads(@RequestParam String statDate) {
        return ApiResponse.ok(businessAnalyticsService.listDailyWorkloads(statDate));
    }

    @GetMapping("/kpis/assessments")
    public ApiResponse<List<KpiAssessmentResponse>> assessments(@RequestParam String month) {
        return ApiResponse.ok(businessAnalyticsService.calculateKpiAssessments(month));
    }

    @GetMapping("/rankings")
    public ApiResponse<List<RankingItemResponse>> rankings(@RequestParam String month,
                                                           @RequestParam String scope,
                                                           @RequestParam(defaultValue = "10") int topN) {
        return ApiResponse.ok(businessAnalyticsService.listRankings(month, scope, topN));
    }

    @PostMapping("/rankings/rebuild")
    public ApiResponse<Void> rebuildRankings(@RequestParam String month) {
        businessAnalyticsService.rebuildRanking(month);
        return ApiResponse.ok(null);
    }

    @GetMapping("/listing-turnover-report")
    public ApiResponse<List<ListingTurnoverReportResponse>> turnoverReports(@RequestParam String month) {
        return ApiResponse.ok(businessAnalyticsService.listTurnoverReports(month));
    }

    @GetMapping("/store-dashboard")
    public ApiResponse<StoreDashboardResponse> storeDashboard(@RequestParam String storeName) {
        return ApiResponse.ok(businessAnalyticsService.getStoreDashboard(storeName));
    }

    @GetMapping("/big-dashboard")
    public ApiResponse<BigDataDashboardResponse> bigDashboard(@RequestParam String month,
                                                              @RequestParam String startDate,
                                                              @RequestParam String endDate,
                                                              @RequestParam(required = false) String regionName,
                                                              @RequestParam(defaultValue = "5") int topN) {
        return ApiResponse.ok(businessAnalyticsService.getBigDataDashboard(month, startDate, endDate, regionName, topN));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("business-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
