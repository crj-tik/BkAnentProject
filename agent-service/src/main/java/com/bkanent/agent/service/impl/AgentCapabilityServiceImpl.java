package com.bkanent.agent.service.impl;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.AgentMcpNames;
import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.service.AgentCapabilityService;
import com.bkanent.agent.service.AgentChatService;
import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.model.MarketingContentDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AgentCapabilityServiceImpl 服务实现类。
 */
@Service
public class AgentCapabilityServiceImpl implements AgentCapabilityService {

    private static final String MARKETING_COPY_SYSTEM_PROMPT = """
            你是房产营销文案助手。
            请根据用户提供的需求，生成可以直接发布的中文房源营销文案。
            文案要真实、简洁、有吸引力，不允许夸大承诺。
            """;

    private static final String KPI_NARRATIVE_SYSTEM_PROMPT = """
            你是房产业务分析助手。
            请根据 KPI 数据输出管理层可读的月度总结。
            总结必须包含现状判断、风险提示和建议动作。
            """;

    private static final String COMPARE_CONCLUSION_SYSTEM_PROMPT = """
            你是房源对比分析助手。
            请根据输入的房源对比信息输出中文分析结论。
            结论需要包含差异总结、性价比判断、适合人群和决策建议。
            """;

    /**
     * 字段：deepSeekChatService。
     */
    private final AgentChatService agentChatService;
    /**
     * 字段：agentMcpClient。
     */
    private final AgentMcpClient agentMcpClient;
    /**
     * 字段：objectMapper。
     */
    private final ObjectMapper objectMapper;

    /**
     * 构造 AgentCapabilityServiceImpl 实例。
     */
    public AgentCapabilityServiceImpl(AgentChatService agentChatService,
                                      AgentMcpClient agentMcpClient,
                                      ObjectMapper objectMapper) {
        this.agentChatService = agentChatService;
        this.agentMcpClient = agentMcpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布listingByPrompt。
     */
    @Override
    public List<MarketingContentDTO> publishListingByPrompt(String rawInput) {
        String copywriting = agentChatService.call(
                MARKETING_COPY_SYSTEM_PROMPT,
                "请根据以下需求生成一版中文房源营销文案：\n" + rawInput
        );
        AgentMcpCallResult result = agentMcpClient.callTool(
                AgentMcpNames.MARKETING_SERVER,
                AgentMcpNames.PUBLISH_MARKETING_CONTENT,
                Map.of(
                        "listingId", 1L,
                        "copywriting", copywriting,
                        "platforms", "douyin"
                )
        );
        return readList(result.payload().get("contents"), new TypeReference<>() {
        });
    }

    /**
     * 生成kpiNarrative。
     */
    @Override
    public String generateKpiNarrative(List<KpiSummaryDTO> kpis) {
        List<KpiSummaryDTO> source = (kpis == null || kpis.isEmpty())
                ? loadMonthlyKpis("2026-05")
                : kpis;
        return agentChatService.call(
                KPI_NARRATIVE_SYSTEM_PROMPT,
                "请基于以下 KPI 数据生成总结：\n" + formatKpis(source)
        );
    }

    /**
     * 分析listings。
     */
    @Override
    public CompareReportDTO analyzeListings(List<Long> listingIds) {
        String listingIdsText = listingIds == null ? "" : listingIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        AgentMcpCallResult result = agentMcpClient.callTool(
                AgentMcpNames.COMPARE_SERVER,
                AgentMcpNames.COMPARE_LISTINGS,
                Map.of("listingIds", listingIdsText)
        );
        String markdown = String.valueOf(result.payload().getOrDefault("comparisonTableMarkdown", ""));
        String conclusion = String.valueOf(result.payload().getOrDefault("aiConclusion", result.text()));
        return new CompareReportDTO(List.of(), markdown, conclusion);
    }

    /**
     * 生成compareConclusion。
     */
    @Override
    public String generateCompareConclusion(String comparePrompt) {
        return agentChatService.call(COMPARE_CONCLUSION_SYSTEM_PROMPT, comparePrompt);
    }

    /**
     * 加载monthlyKpis。
     */
    private List<KpiSummaryDTO> loadMonthlyKpis(String month) {
        AgentMcpCallResult result = agentMcpClient.callTool(
                AgentMcpNames.BUSINESS_SERVER,
                AgentMcpNames.QUERY_MONTHLY_KPIS,
                Map.of("month", month)
        );
        return readList(result.payload().get("kpis"), new TypeReference<>() {
        });
    }

    /**
     * 格式化kpis。
     */
    private String formatKpis(List<KpiSummaryDTO> kpis) {
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

    /**
     * 读取list。
     */
    private <T> List<T> readList(Object value, TypeReference<List<T>> typeReference) {
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, typeReference);
    }
}
