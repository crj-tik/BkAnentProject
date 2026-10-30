package com.bkanent.business.mcp;

import com.bkanent.business.service.BusinessAnalyticsService;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.tool.McpTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlatformOperationsAnalyticsMcpTools implements McpTool {

    private final BusinessAnalyticsService businessAnalyticsService;

    public PlatformOperationsAnalyticsMcpTools(BusinessAnalyticsService businessAnalyticsService) {
        this.businessAnalyticsService = businessAnalyticsService;
    }

    @Tool(description = "查询指定月份的门店经营 KPI 汇总，返回已登记的成交量、新增房源、新增客户和完成率。")
    public List<KpiSummaryDTO> queryMonthlyKpis(
            @ToolParam(description = "月份，格式 yyyy-MM，例如 2026-01") String month) {
        return businessAnalyticsService.listMonthlyKpis(month);
    }
}
