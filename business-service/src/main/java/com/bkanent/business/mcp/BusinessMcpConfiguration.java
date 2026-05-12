package com.bkanent.business.mcp;

import com.bkanent.business.service.BusinessAnalyticsService;
import com.bkanent.common.model.KpiSummaryDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class BusinessMcpConfiguration {

    public static final String SERVER_NAME = "business-mcp-server";
    public static final String TOOL_NAME = "queryMonthlyKpis";

    @Bean
    public McpJsonMapper businessMcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper.copy());
    }

    @Bean
    public HttpServletStreamableServerTransportProvider businessMcpTransportProvider(McpJsonMapper mcpJsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .jsonMapper(mcpJsonMapper)
                .build();
    }

    @Bean(destroyMethod = "closeGracefully")
    public McpSyncServer businessMcpServer(HttpServletStreamableServerTransportProvider transportProvider,
                                           McpJsonMapper mcpJsonMapper,
                                           BusinessAnalyticsService businessAnalyticsService) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .title("Query Monthly KPIs")
                .description("查询指定月份的经纪人 KPI 汇总信息。")
                .inputSchema(mcpJsonMapper, "{\"type\":\"object\",\"properties\":{\"month\":{\"type\":\"string\"}},\"required\":[\"month\"]}")
                .outputSchema(mcpJsonMapper, "{\"type\":\"object\",\"properties\":{\"resultText\":{\"type\":\"string\"},\"month\":{\"type\":\"string\"},\"kpis\":{\"type\":\"array\"}},\"required\":[\"resultText\",\"month\",\"kpis\"]}")
                .build();
        return McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, "1.0.0")
                .tool(tool, (exchange, arguments) -> {
                    String month = String.valueOf(arguments.get("month"));
                    List<KpiSummaryDTO> kpis = businessAnalyticsService.listMonthlyKpis(month);
                    String text = formatKpis(kpis);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(text)
                            .structuredContent(Map.of(
                                    "resultText", text,
                                    "month", month,
                                    "kpis", kpis
                            ))
                            .build();
                })
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> businessMcpServlet(
            HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, "/mcp", "/mcp/*");
    }

    private static String formatKpis(List<KpiSummaryDTO> kpis) {
        if (kpis == null || kpis.isEmpty()) {
            return "未查询到 KPI 汇总数据。";
        }
        StringBuilder builder = new StringBuilder();
        for (KpiSummaryDTO kpi : kpis) {
            builder.append("员工 ")
                    .append(kpi.employeeName())
                    .append("，成交 ")
                    .append(kpi.closedDeals())
                    .append(" 单，新增房源 ")
                    .append(kpi.newListings())
                    .append(" 套，新增客户 ")
                    .append(kpi.newCustomers())
                    .append(" 个，完成率 ")
                    .append(kpi.completionRate())
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }
}
