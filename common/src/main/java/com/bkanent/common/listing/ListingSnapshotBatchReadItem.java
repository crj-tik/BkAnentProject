package com.bkanent.common.listing;

import java.io.Serializable;

/** Batch read item preserves the requested reference without exposing inaccessible source facts. */
public record ListingSnapshotBatchReadItem(
        ListingRef listingRef,
        ListingSourceStatus status,
        ListingSourceSnapshot snapshot) implements Serializable {
    public ListingSnapshotBatchReadItem {
        if (listingRef == null || status == null
                || status == ListingSourceStatus.CURRENT && snapshot == null
                || status != ListingSourceStatus.CURRENT && snapshot != null) {
            throw new IllegalArgumentException("Invalid listing batch read item");
        }
    }
}
