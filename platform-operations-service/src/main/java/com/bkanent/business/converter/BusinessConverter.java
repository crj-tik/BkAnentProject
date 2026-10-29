package com.bkanent.business.converter;

import com.bkanent.business.entity.EmployeeDailyWorkloadEntity;
import com.bkanent.business.entity.EmployeeKpiStatEntity;
import com.bkanent.business.entity.ListingTurnoverStatEntity;
import com.bkanent.business.entity.StoreDashboardSnapshotEntity;
import com.bkanent.business.model.EmployeeDailyWorkloadResponse;
import com.bkanent.business.model.KpiAssessmentResponse;
import com.bkanent.business.model.ListingTurnoverReportResponse;
import com.bkanent.business.model.StoreDashboardResponse;
import com.bkanent.common.model.KpiSummaryDTO;
import org.springframework.stereotype.Component;

/**
 * 业务模块对象转换器。
 */
@Component
public class BusinessConverter {

    public KpiSummaryDTO toKpiSummary(EmployeeKpiStatEntity entity) {
        return new KpiSummaryDTO(
                entity.getEmployeeId(),
                entity.getEmployeeName(),
                entity.getClosedDeals(),
                entity.getNewListings(),
                entity.getNewCustomers(),
                entity.getCompletionRate()
        );
    }

    public KpiAssessmentResponse toAssessment(EmployeeKpiStatEntity entity) {
        return new KpiAssessmentResponse(
                entity.getEmployeeId(),
                entity.getEmployeeName(),
                entity.getStoreName(),
                entity.getRegionName(),
                entity.getSaleDeals(),
                entity.getRentalDeals(),
                entity.getClosedDeals(),
                entity.getPerformanceAmount(),
                entity.getCompletionRate(),
                entity.getConversionRate(),
                entity.getSatisfactionScore()
        );
    }

    public EmployeeDailyWorkloadResponse toDailyWorkload(EmployeeDailyWorkloadEntity entity) {
        return new EmployeeDailyWorkloadResponse(
                entity.getEmployeeId(),
                entity.getEmployeeName(),
                entity.getStoreName(),
                entity.getRegionName(),
                entity.getStatDate(),
                entity.getViewingCount(),
                entity.getNewListings(),
                entity.getNewCustomers(),
                entity.getFollowUpCount()
        );
    }

    public ListingTurnoverReportResponse toTurnoverReport(ListingTurnoverStatEntity entity) {
        return new ListingTurnoverReportResponse(
                entity.getListingId(),
                entity.getListingTitle(),
                entity.getStoreName(),
                entity.getRegionName(),
                entity.getStatMonth(),
                entity.getListingToViewingDays(),
                entity.getViewingToDealDays(),
                entity.getTotalTurnoverDays(),
                entity.getTurnoverStatus()
        );
    }

    public StoreDashboardResponse toDashboard(StoreDashboardSnapshotEntity entity) {
        return new StoreDashboardResponse(
                entity.getStoreName(),
                entity.getRegionName(),
                entity.getStatDate(),
                entity.getActiveListingCount(),
                entity.getTodayViewingCount(),
                entity.getTodayNewCustomerCount(),
                entity.getTodayDealCount(),
                entity.getTodayPerformanceAmount(),
                entity.getSatisfactionScore()
        );
    }
}
