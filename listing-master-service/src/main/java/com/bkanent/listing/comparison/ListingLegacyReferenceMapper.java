package com.bkanent.listing.comparison;

import com.bkanent.common.listing.ListingRef;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lossless compatibility mapping for the retired Long-only Compare request. */
public final class ListingLegacyReferenceMapper {
    private ListingLegacyReferenceMapper() { }

    public static List<ListingRef> fromIds(List<Long> ids) {
        if (ids == null || ids.size() < 2 || ids.size() > 6) {
            throw ListingComparisonException.invalidRequest();
        }
        Set<Long> unique = new HashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0 || !unique.add(id)) throw ListingComparisonException.invalidRequest();
        }
        return ids.stream().map(id -> new ListingRef("legacy-local", Long.toString(id))).toList();
    }
}
