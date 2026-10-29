package com.bkanent.listing.controller;

import com.bkanent.common.listing.ListingComparisonCreateRequest;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.listing.comparison.ListingComparisonService;
import com.bkanent.listing.comparison.model.ListingComparisonLegacyReportResponse;
import com.bkanent.listing.comparison.model.ListingComparisonLegacySummaryResponse;
import com.bkanent.listing.comparison.model.ListingComparisonMetric;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
import com.bkanent.listing.comparison.model.ListingComparisonRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authorized, legacy-local-only REST adapter. There are deliberately no share-code or anonymous routes. */
@RestController
@RequestMapping("/compare")
public class ListingComparisonLegacyController {
    private final ListingComparisonService service;

    public ListingComparisonLegacyController(ListingComparisonService service) { this.service = service; }

    @GetMapping("/listings")
    public ApiResponse<ListingComparisonLegacySummaryResponse> compareListings(@RequestParam List<Long> listingIds) {
        return ApiResponse.ok(service.createLegacyDto(listingIds));
    }

    @GetMapping("/reports")
    public ApiResponse<ListingComparisonLegacyReportResponse> createReport(
            @RequestParam List<Long> listingIds,
            @RequestParam(defaultValue = "true") boolean includeAiConclusion) {
        ListingComparisonReportResponse report = service.createLegacyLocal(listingIds, includeAiConclusion);
        List<ListingComparisonLegacyReportResponse.Row> rows = report.rows().stream().map(this::legacyRow).toList();
        List<ListingComparisonLegacyReportResponse.Metric> metrics = report.metrics().stream()
                .map(this::legacyMetric).toList();
        var response = new ListingComparisonLegacyReportResponse(service.legacyListings(report), columns(), rows,
                metrics, report.comparisonTableMarkdown(), report.conclusion(), null, null, report.pdfDownloadPath());
        return ApiResponse.ok(response);
    }

    private ListingComparisonLegacyReportResponse.Row legacyRow(ListingComparisonRow row) {
        if (!"legacy-local".equals(row.listingRef().sourceSystem())) {
            throw com.bkanent.listing.comparison.ListingComparisonException.invalidRequest();
        }
        return new ListingComparisonLegacyReportResponse.Row(Long.parseLong(row.listingRef().sourceId()),
                row.title(), row.values());
    }

    private List<ListingComparisonLegacyReportResponse.Column> columns() {
        return List.of(new ListingComparisonLegacyReportResponse.Column("area", "面积（㎡）"),
                new ListingComparisonLegacyReportResponse.Column("unitPrice", "单价（元/㎡）"),
                new ListingComparisonLegacyReportResponse.Column("floorLevel", "楼层"),
                new ListingComparisonLegacyReportResponse.Column("decoration", "装修"),
                new ListingComparisonLegacyReportResponse.Column("schoolZone", "学区"),
                new ListingComparisonLegacyReportResponse.Column("traffic", "交通"),
                new ListingComparisonLegacyReportResponse.Column("layout", "户型"),
                new ListingComparisonLegacyReportResponse.Column("verificationStatus", "核验状态"),
                new ListingComparisonLegacyReportResponse.Column("status", "状态"));
    }

    private ListingComparisonLegacyReportResponse.Metric legacyMetric(ListingComparisonMetric metric) {
        String label = switch (metric.metric()) {
            case "lowestTotalPriceCny" -> "最低总价（元）";
            case "highestTotalPriceCny" -> "最高总价（元）";
            case "lowestUnitPriceCnyPerSquareMetre" -> "最低单价（元/㎡）";
            default -> "最高单价（元/㎡）";
        };
        return new ListingComparisonLegacyReportResponse.Metric(label, metric.value());
    }
}
