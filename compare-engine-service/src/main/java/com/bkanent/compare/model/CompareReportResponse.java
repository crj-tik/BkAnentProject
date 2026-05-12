package com.bkanent.compare.model;

import com.bkanent.common.model.ListingDTO;

import java.util.List;

/**
 * 房源对比报告响应。
 */
public record CompareReportResponse(
        /** 业务属性：listings。 */
        List<ListingDTO> listings,
        /** 业务属性：columns。 */
        List<CompareColumnResponse> columns,
        /** 业务属性：rows。 */
        List<CompareRowResponse> rows,
        /** 业务属性：metrics。 */
        List<CompareMetricResponse> metrics,
        /** 业务属性：comparisonTableMarkdown。 */
        String comparisonTableMarkdown,
        /** 业务属性：aiConclusion。 */
        String aiConclusion,
        /** 业务属性：shareCode。 */
        String shareCode,
        /** 业务属性：shareLink。 */
        String shareLink,
        /** 业务属性：pdfDownloadUrl。 */
        String pdfDownloadUrl
) {
}
