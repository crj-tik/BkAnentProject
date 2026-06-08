package com.bkanent.compare.mcp;

import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.tool.McpTool;
import com.bkanent.compare.service.CompareAnalysisService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class CompareMcpTools implements McpTool {

    private final CompareAnalysisService compareAnalysisService;

    public CompareMcpTools(CompareAnalysisService compareAnalysisService) {
        this.compareAnalysisService = compareAnalysisService;
    }

    @Tool(description = "对多个房源做结构化对比并返回总结。包含对比表格和AI结论。")
    public CompareReportDTO compareListings(
            @ToolParam(description = "Comma-separated listing IDs, e.g. '101,102,103'") String listingIds) {
        List<Long> ids = Arrays.stream(listingIds.split("[,，、\\s]+"))
                .filter(s -> s != null && !s.isBlank())
                .map(Long::valueOf)
                .toList();
        return compareAnalysisService.generateRpcCompareReport(ids);
    }
}
