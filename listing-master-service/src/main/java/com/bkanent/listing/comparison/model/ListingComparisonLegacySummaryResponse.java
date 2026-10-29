package com.bkanent.listing.comparison.model;

import com.bkanent.common.model.ListingDTO;

import java.util.List;

/** Three-field response shape retained for the authorized legacy Listing comparison route. */
public record ListingComparisonLegacySummaryResponse(
        List<ListingDTO> listings,
        String comparisonTableMarkdown,
        String aiConclusion) {
}
