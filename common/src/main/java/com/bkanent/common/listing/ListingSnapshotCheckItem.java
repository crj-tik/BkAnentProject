package com.bkanent.common.listing;

import java.io.Serializable;

public record ListingSnapshotCheckItem(
        ListingRef listingRef,
        String expectedRevision,
        ListingSourceStatus status) implements Serializable {
    public ListingSnapshotCheckItem {
        if (listingRef == null || expectedRevision == null || expectedRevision.isBlank() || status == null) {
            throw new IllegalArgumentException("Invalid listing snapshot check item");
        }
    }
}
