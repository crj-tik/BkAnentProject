package com.bkanent.business.service;

import com.bkanent.business.entity.EmployeeKpiStatEntity;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.rpc.BusinessRpcService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
public class BusinessRpcServiceImpl implements BusinessRpcService {

    private final KpiStatService kpiStatService;

    public BusinessRpcServiceImpl(KpiStatService kpiStatService) {
        this.kpiStatService = kpiStatService;
    }

    @Override
    public List<KpiSummaryDTO> getMonthlyKpis(String month) {
        return kpiStatService.listByMonth(month).stream().map(this::toDto).toList();
    }

    private KpiSummaryDTO toDto(EmployeeKpiStatEntity entity) {
        return new KpiSummaryDTO(
                entity.getEmployeeId(),
                entity.getEmployeeName(),
                entity.getClosedDeals(),
                entity.getNewListings(),
                entity.getNewCustomers(),
                entity.getCompletionRate()
        );
    }
}
