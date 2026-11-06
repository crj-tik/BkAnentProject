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
        String pdfDownloadPath,
        List<ListingComparisonCard> cards,
        List<ListingComparisonMetricWinner> metricWinners) {
    public ListingComparisonReportResponse {
        sources = List.copyOf(sources);
        rows = List.copyOf(rows);
        metrics = List.copyOf(metrics);
        // 卡片投影在读取边界计算、从不存储；旧报告 JSON 缺这两个字段时反序列化为空。
        cards = cards == null ? List.of() : List.copyOf(cards);
        metricWinners = metricWinners == null ? List.of() : List.copyOf(metricWinners);
    }

    /** 兼容构造：无卡片投影的既有调用方（构建与存量反序列化）。 */
    public ListingComparisonReportResponse(
            String reportId, String contractVersion, String rulesVersion, Instant createdAt,
            ListingDataMode dataMode, List<ListingSourceSnapshot> sources,
            List<ListingComparisonRow> rows, List<ListingComparisonMetric> metrics,
            String comparisonTableMarkdown, String conclusion, String pdfDownloadPath) {
        this(reportId, contractVersion, rulesVersion, createdAt, dataMode, sources, rows, metrics,
                comparisonTableMarkdown, conclusion, pdfDownloadPath, null, null);
    }
}
