package com.bkanent.compare.tool;

import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.compare.model.CompareReportResponse;
import com.bkanent.compare.service.CompareAnalysisService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class CompareTools {

    private final CompareAnalysisService compareAnalysisService;

    public CompareTools(CompareAnalysisService compareAnalysisService) {
        this.compareAnalysisService = compareAnalysisService;
    }

    @Tool(description = "Compare multiple property listings side-by-side. Returns a comparison report with key metrics, pros/cons, and an AI-generated conclusion.")
    public CompareReportDTO compareListings(
            @ToolParam(description = "Comma-separated listing IDs, e.g. '101,102,103'") String listingIds) {
        List<Long> ids = Arrays.stream(listingIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
        return compareAnalysisService.generateRpcCompareReport(ids);
    }

    @Tool(description = "Get a previously shared comparison report by its share code.")
    public CompareReportResponse getSharedReport(
            @ToolParam(description = "Share code from a previously generated report") String shareCode) {
        return compareAnalysisService.getSharedReport(shareCode);
    }
}