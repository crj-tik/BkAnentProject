package com.bkanent.common.listing;

import java.io.Serializable;
import java.util.List;

/** Version-bound comparison input; each source revision is required. */
public record ListingComparisonCreateRequest(
        String contractVersion,
        List<ListingSourceVersionRef> refs,
        boolean includeConclusion) implements Serializable {
    public ListingComparisonCreateRequest {
        refs = refs == null ? null : List.copyOf(refs);
    }
}
