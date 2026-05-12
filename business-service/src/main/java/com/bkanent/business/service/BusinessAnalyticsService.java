package com.bkanent.business.service;

import com.bkanent.business.model.BigDataDashboardResponse;
import com.bkanent.business.model.EmployeeDailyWorkloadResponse;
import com.bkanent.business.model.KpiAssessmentResponse;
import com.bkanent.business.model.ListingTurnoverReportResponse;
import com.bkanent.business.model.RankingItemResponse;
import com.bkanent.business.model.StoreDashboardResponse;
import com.bkanent.common.model.KpiSummaryDTO;

import java.util.List;

/**
 * 职能业务分析服务接口。
 */
public interface BusinessAnalyticsService {

    /**
     * 业务方法：listMonthlyKpis。
     */
    List<KpiSummaryDTO> listMonthlyKpis(String month);

    /**
     * 业务方法：listDailyWorkloads。
     */
    List<EmployeeDailyWorkloadResponse> listDailyWorkloads(String statDate);

    /**
     * 业务方法：calculateKpiAssessments。
     */
    List<KpiAssessmentResponse> calculateKpiAssessments(String month);

    /**
     * 业务方法：listRankings。
     */
    List<RankingItemResponse> listRankings(String month, String scope, int topN);

    /**
     * 业务方法：listTurnoverReports。
     */
    List<ListingTurnoverReportResponse> listTurnoverReports(String month);

    /**
     * 业务方法：getStoreDashboard。
     */
    StoreDashboardResponse getStoreDashboard(String storeName);

    /**
     * 业务方法：getBigDataDashboard。
     */
    BigDataDashboardResponse getBigDataDashboard(String month, String startDate, String endDate, String regionName, int topN);

    /**
     * 业务方法：rebuildRanking。
     */
    void rebuildRanking(String month);
}
