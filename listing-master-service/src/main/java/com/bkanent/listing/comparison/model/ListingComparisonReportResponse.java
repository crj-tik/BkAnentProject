package com.bkanent.listing.comparison.model;

import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingSourceSnapshot;

import java.time.Instant;
import java.util.List;

/** Immutable owner-scoped report bound to exact stable source revisions. */
public record ListingComparisonReportResponse(
        String reportId,
        String contractVersion,
        String rulesVersion,
        Instant createdAt,
        ListingDataMode dataMode,
        List<ListingSourceSnapshot> sources,
        List<ListingComparisonRow> rows,
        List<ListingComparisonMetric> metrics,
        String comparisonTableMarkdown,
        String conclusion,
        String pdfDownloadPath) {
    public ListingComparisonReportResponse {
        sources = List.copyOf(sources);
        rows = List.copyOf(rows);
        metrics = List.copyOf(metrics);
    }
}
