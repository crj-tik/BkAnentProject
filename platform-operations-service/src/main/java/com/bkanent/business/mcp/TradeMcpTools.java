package com.bkanent.business.mcp;

import com.bkanent.business.service.BusinessAnalyticsService;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.tool.McpTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TradeMcpTools implements McpTool {

    private final BusinessAnalyticsService businessAnalyticsService;

    public TradeMcpTools(BusinessAnalyticsService businessAnalyticsService) {
        this.businessAnalyticsService = businessAnalyticsService;
    }

    @Tool(description = "查询指定月份的经纪人 KPI 汇总信息。返回每个经纪人的成交量、新增房源、新增客户和完成率。")
    public List<KpiSummaryDTO> queryMonthlyKpis(
            @ToolParam(description = "Month in yyyy-MM format, e.g. 2026-01") String month) {
        return businessAnalyticsService.listMonthlyKpis(month);
    }
}
