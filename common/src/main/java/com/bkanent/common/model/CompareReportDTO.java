package com.bkanent.common.model;

import java.util.List;

/**
 * CompareReportDTO 数据传输对象。
 */

public record CompareReportDTO(
        /** 业务属性：listings。 */
        List<ListingDTO> listings,
        /** 业务属性：comparisonTableMarkdown。 */
        String comparisonTableMarkdown,
        /** 业务属性：aiConclusion。 */
        String aiConclusion
) {
}

