package com.bkanent.business.rpc;

import com.bkanent.business.service.BusinessAnalyticsService;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.rpc.BusinessRpcService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * 职能业务 RPC 服务实现。
 */
@DubboService
public class BusinessRpcServiceImpl implements BusinessRpcService {

    private final BusinessAnalyticsService businessAnalyticsService;

    public BusinessRpcServiceImpl(BusinessAnalyticsService businessAnalyticsService) {
        this.businessAnalyticsService = businessAnalyticsService;
    }

    @Override
    public List<KpiSummaryDTO> getMonthlyKpis(String month) {
        return businessAnalyticsService.listMonthlyKpis(month);
    }
}
