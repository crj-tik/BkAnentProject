package com.bkanent.common.listing;

import java.io.Serializable;

/** Exact source reference, with an optional expected revision for batch reads. */
public record ListingSourceVersionRef(ListingRef listingRef, String expectedRevision) implements Serializable {
    public ListingSourceVersionRef {
        if (listingRef == null || expectedRevision != null
                && (expectedRevision.isBlank() || expectedRevision.length() > 255)) {
            throw new IllegalArgumentException("Invalid listing source version reference");
        }
    }
}
