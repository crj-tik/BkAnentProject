package com.bkanent.listing.source;

/** Derived only from the currently verified Auth context. */
public record ListingAccessScope(long actorId, Long brokerId, boolean businessWideRead) {
    public ListingAccessScope {
        if (actorId <= 0 || !businessWideRead && (brokerId == null || brokerId <= 0)) {
            throw new IllegalArgumentException("Invalid listing access scope");
        }
    }
}
