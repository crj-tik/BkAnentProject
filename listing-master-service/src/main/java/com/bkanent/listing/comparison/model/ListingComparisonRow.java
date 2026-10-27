package com.bkanent.listing.comparison.model;

import com.bkanent.common.listing.ListingRef;

import java.util.Map;

public record ListingComparisonRow(ListingRef listingRef, String title, Map<String, String> values) {
    public ListingComparisonRow {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
