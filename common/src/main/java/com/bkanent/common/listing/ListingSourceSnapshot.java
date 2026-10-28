package com.bkanent.common.listing;

import java.io.Serializable;
import java.time.Instant;

/** Immutable, versioned listing facts from one explicitly selected source. */
public record ListingSourceSnapshot(
        ListingRef listingRef,
        String revision,
        String contentHash,
        Instant observedAt,
        ListingDataMode dataMode,
        String datasetId,
        ListingSnapshotFacts facts) implements Serializable {
    public ListingSourceSnapshot {
        if (listingRef == null || revision == null || revision.isBlank() || revision.length() > 255
                || contentHash == null || !contentHash.matches("[0-9a-f]{64}") || observedAt == null
                || dataMode == null || datasetId == null || !datasetId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || facts == null) {
            throw new IllegalArgumentException("Invalid listing source snapshot");
        }
    }
}
