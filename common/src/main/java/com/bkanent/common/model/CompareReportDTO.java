package com.bkanent.common.model;

import java.util.List;

public record CompareReportDTO(
        List<ListingDTO> listings,
        String comparisonTableMarkdown,
        String aiConclusion
) {
}
