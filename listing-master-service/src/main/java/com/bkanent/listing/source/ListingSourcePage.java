package com.bkanent.listing.source;

import com.bkanent.common.listing.ListingSourceSnapshot;

import java.util.List;

public record ListingSourcePage(List<ListingSourceSnapshot> items, String nextCursor) {
    public ListingSourcePage {
        items = List.copyOf(items);
    }
}
