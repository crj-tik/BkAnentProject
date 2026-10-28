package com.bkanent.listing.comparison.model;

import com.bkanent.common.model.ListingDTO;

import java.util.List;
import java.util.Map;

/** JSON-shape compatibility for authorized legacy /compare/reports callers. */
public record ListingComparisonLegacyReportResponse(
        List<ListingDTO> listings,
        List<Column> columns,
        List<Row> rows,
        List<Metric> metrics,
        String comparisonTableMarkdown,
        String aiConclusion,
        String shareCode,
        String shareLink,
        String pdfDownloadUrl) {
    public record Column(String key, String label) { }
    public record Row(Long listingId, String listingTitle, Map<String, String> values) { }
    public record Metric(String metricName, String summaryValue) { }
}
